/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dromara.hertzbeat.alert.calculate;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.Expression;
import com.googlecode.aviator.exception.CompileExpressionErrorException;
import com.googlecode.aviator.exception.ExpressionRuntimeException;
import com.googlecode.aviator.exception.ExpressionSyntaxErrorException;
import org.dromara.hertzbeat.alert.AlerterWorkerPool;
import org.dromara.hertzbeat.alert.reduce.AlarmCommonReduce;
import org.dromara.hertzbeat.alert.service.AlertService;
import org.dromara.hertzbeat.common.entity.manager.TagItem;
import org.dromara.hertzbeat.common.queue.CommonDataQueue;
import org.dromara.hertzbeat.alert.dao.AlertMonitorDao;
import org.dromara.hertzbeat.common.entity.alerter.Alert;
import org.dromara.hertzbeat.common.entity.alerter.AlertDefine;
import org.dromara.hertzbeat.alert.service.AlertDefineService;
import org.dromara.hertzbeat.alert.util.AlertTemplateUtil;
import org.dromara.hertzbeat.common.entity.manager.Monitor;
import org.dromara.hertzbeat.common.entity.message.CollectRep;
import org.dromara.hertzbeat.common.constants.CommonConstants;
import org.dromara.hertzbeat.common.support.event.SystemConfigChangeEvent;
import org.dromara.hertzbeat.common.util.CommonUtil;
import org.dromara.hertzbeat.common.util.ResourceBundleUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.persistence.criteria.Predicate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.dromara.hertzbeat.common.constants.CommonConstants.ALERT_STATUS_CODE_PENDING;
import static org.dromara.hertzbeat.common.constants.CommonConstants.ALERT_STATUS_CODE_SOLVED;

/**
 * Calculate alarms based on the alarm definition rules and collected data
 * 根据告警定义规则和采集数据匹配计算告警
 *
 *
 */
@Component
@Slf4j
public class CalculateAlarm {

    private static final String SYSTEM_VALUE_ROW_COUNT = "system_value_row_count";
    
    /**
     * The alarm in the process is triggered
     * 触发中告警信息
     * key - monitorId+alertDefineId 为普通阈值告警 ｜ The alarm is a common threshold alarm
     * key - monitorId 为监控状态可用性可达性告警 ｜ Indicates the monitoring status availability reachability alarm
     */
    public Map<String, Alert> triggeredAlertMap;
    public Set<Long> unAvailableMonitors;
    private final AlerterWorkerPool workerPool;
    private final CommonDataQueue dataQueue;
    private final AlertDefineService alertDefineService;
    private final AlarmCommonReduce alarmCommonReduce;
    private ResourceBundle bundle;
    private final AlertService alertService;

    public CalculateAlarm(AlerterWorkerPool workerPool, CommonDataQueue dataQueue,
                          AlertDefineService alertDefineService, AlertMonitorDao monitorDao,
                          AlarmCommonReduce alarmCommonReduce,  AlertService alertService) {
        this.workerPool = workerPool;
        this.dataQueue = dataQueue;
        this.alarmCommonReduce = alarmCommonReduce;
        this.alertDefineService = alertDefineService;
        this.alertService = alertService;
        this.bundle = ResourceBundleUtil.getBundle("alerter");
        this.triggeredAlertMap = new ConcurrentHashMap<>(128);
        this.unAvailableMonitors = Collections.synchronizedSet(new HashSet<>(16));
        // Initialize stateAlertMap
        // 初始化stateAlertMap
        List<Monitor> monitors = monitorDao.findMonitorsByStatus(CommonConstants.UN_AVAILABLE_CODE);
        if (monitors != null) {
            for (Monitor monitor : monitors) {
                this.unAvailableMonitors.add(monitor.getId());
            }
        }
        startCalculate();
    }

    private void startCalculate() {
        Runnable runnable = () -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    CollectRep.MetricsData metricsData = dataQueue.pollMetricsDataToAlerter();
                    if (metricsData != null) {
                        calculate(metricsData);
                    }
                } catch (Exception e) {
                    log.error(e.getMessage());
                }
            }
        };
        workerPool.executeJob(runnable);
        workerPool.executeJob(runnable);
        workerPool.executeJob(runnable);
    }

    private void calculate(CollectRep.MetricsData metricsData) {
        long currentTimeMilli = System.currentTimeMillis();
        long monitorId = metricsData.getId();
        String app = metricsData.getApp();
        String metrics = metricsData.getMetrics();
        // If the indicator group whose scheduling priority is 0 has the status of collecting response data UN_REACHABLE/UN_CONNECTABLE, the highest severity alarm is generated to monitor the status change
        // 先判断调度优先级为0的指标组采集响应数据状态 UN_REACHABLE/UN_CONNECTABLE 则需发最高级别告警进行监控状态变更
        if (metricsData.getPriority() == 0) {
            handlerAvailableMetrics(monitorId, app, metrics, metricsData);
        }
        // Query the alarm definitions associated with the indicator set of the monitoring type
        // 查出此监控类型下的此指标集合下关联配置的告警定义信息
        // field - define[]
        Map<String, List<AlertDefine>> defineMap = alertDefineService.getMonitorBindAlertDefines(monitorId, app, metrics);
        if (defineMap.isEmpty()) {
            return;
        }
        List<CollectRep.Field> fields = metricsData.getFieldsList();
        Map<String, Object> fieldValueMap = new HashMap<>(16);
        int valueRowCount = metricsData.getValuesCount();
        for (Map.Entry<String, List<AlertDefine>> entry : defineMap.entrySet()) {
            List<AlertDefine> defines = entry.getValue();
            for (AlertDefine define : defines) {
                String expr = define.getExpr();
                if (!StringUtils.hasText(expr)) {
                    continue;
                }
                if (expr.contains(SYSTEM_VALUE_ROW_COUNT)) {
                    fieldValueMap.put(SYSTEM_VALUE_ROW_COUNT, valueRowCount);
                    try {
                        boolean match = execAlertExpression(fieldValueMap, expr);
                        if (match) {
                            // If the threshold rule matches, the number of times the threshold has been triggered is determined and an alarm is triggered
                            // 阈值规则匹配，判断已触发阈值次数，触发告警
                            afterThresholdRuleMatch(currentTimeMilli, monitorId, app, metrics, fieldValueMap, define);
                            // 若此阈值已被触发，则其它数据行的触发忽略
                            continue;
                        }
                    } catch (Exception e) {
                        log.warn(e.getMessage(), e);
                    }
                }
                for (CollectRep.ValueRow valueRow : metricsData.getValuesList()) {
                    if (!valueRow.getColumnsList().isEmpty()) {
                        fieldValueMap.clear();
                        fieldValueMap.put(SYSTEM_VALUE_ROW_COUNT, valueRowCount);
                        String instance = valueRow.getInstance();
                        if (!"".equals(instance)) {
                            fieldValueMap.put("instance", instance);
                        }
                        for (int index = 0; index < valueRow.getColumnsList().size(); index++) {
                            String valueStr = valueRow.getColumns(index);
                            if (!CommonConstants.NULL_VALUE.equals(valueStr)) {
                                CollectRep.Field field = fields.get(index);
                                if (field.getType() == CommonConstants.TYPE_NUMBER) {
                                    Double doubleValue = CommonUtil.parseStrDouble(valueStr);
                                    if (doubleValue != null) {
                                        fieldValueMap.put(field.getName(), doubleValue);
                                    }
                                } else {
                                    if (!"".equals(valueStr)) {
                                        fieldValueMap.put(field.getName(), valueStr);
                                    }
                                }   
                            }
                        }
                        try {
                            boolean match = execAlertExpression(fieldValueMap, expr);
                            if (match) {
                                // If the threshold rule matches, the number of times the threshold has been triggered is determined and an alarm is triggered
                                // 阈值规则匹配，判断已触发阈值次数，触发告警
                                afterThresholdRuleMatch(currentTimeMilli, monitorId, app, metrics, fieldValueMap, define);
                                // 若此阈值已被触发，则其它数据行的触发忽略
                                break;
                            }
                        } catch (Exception e) {
                            log.warn(e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }
    
    private void afterThresholdRuleMatch(long currentTimeMilli, long monitorId, String app, String metrics, Map<String, Object> fieldValueMap, AlertDefine define) {
        String monitorAlertKey = String.valueOf(monitorId) + define.getId();
        Alert triggeredAlert = triggeredAlertMap.get(monitorAlertKey);
        if (triggeredAlert != null) {
            int times = triggeredAlert.getTriggerTimes() + 1;
            triggeredAlert.setTriggerTimes(times);
            triggeredAlert.setFirstAlarmTime(currentTimeMilli);
            triggeredAlert.setLastAlarmTime(currentTimeMilli);
            int defineTimes = define.getTimes() == null ? 1 : define.getTimes();
            if (times >= defineTimes) {
                triggeredAlertMap.remove(monitorAlertKey);
                alarmCommonReduce.reduceAndSendAlarm(triggeredAlert.clone());
            }
        } else {
            fieldValueMap.put("app", app);
            fieldValueMap.put("metrics", metrics);
            fieldValueMap.put("metric", define.getField());
            Map<String, String> tags = new HashMap<>(6);
            tags.put(CommonConstants.TAG_MONITOR_ID, String.valueOf(monitorId));
            tags.put(CommonConstants.TAG_MONITOR_APP, app);
            if (define.getTags() != null && !define.getTags().isEmpty()) {
                for (TagItem tagItem : define.getTags()) {
                    fieldValueMap.put(tagItem.getName(), tagItem.getValue());
                    tags.put(tagItem.getName(), tagItem.getValue());
                }
            }
            Alert alert = Alert.builder()
                                  .tags(tags)
                                  .alertDefineId(define.getId())
                                  .priority(define.getPriority())
                                  .status(ALERT_STATUS_CODE_PENDING)
                                  .target(app + "." + metrics + "." + define.getField())
                                  .triggerTimes(1)
                                  .firstAlarmTime(currentTimeMilli)
                                  .lastAlarmTime(currentTimeMilli)
                                  // Keyword matching and substitution in the template
                                  // 模板中关键字匹配替换
                                  .content(AlertTemplateUtil.render(define.getTemplate(), fieldValueMap))
                                  .build();
            int defineTimes = define.getTimes() == null ? 1 : define.getTimes();
            if (1 >= defineTimes) {
                alarmCommonReduce.reduceAndSendAlarm(alert);
            } else {
                triggeredAlertMap.put(monitorAlertKey, alert);
            }
        }
    }
    
    private boolean execAlertExpression(Map<String, Object> fieldValueMap, String expr) {
        Boolean match = false;
        try {
            Expression expression = AviatorEvaluator.compile(expr, true);
            match = (Boolean) expression.execute(fieldValueMap);
        } catch (CompileExpressionErrorException |
                 ExpressionSyntaxErrorException compileException) {
            log.error("Alert Define Rule: {} Compile Error: {}.", expr, compileException.getMessage());
        } catch (ExpressionRuntimeException expressionRuntimeException) {
            log.error("Alert Define Rule: {} Run Error: {}.", expr, expressionRuntimeException.getMessage());
        } catch (Exception e) {
            log.error("Alert Define Rule: {} Run Error: {}.", e, e.getMessage());
        }
        return match;
    }
    
    private void handlerAvailableMetrics(long monitorId, String app, String metrics, CollectRep.MetricsData metricsData) {
        if (metricsData.getCode() != CollectRep.Code.SUCCESS) {
            // Collection and abnormal
            // 采集异常
            if (metricsData.getCode() == CollectRep.Code.UN_AVAILABLE) {
                // The todo collector is unavailable
                // todo 采集器不可用
                return;
            } else if (metricsData.getCode() == CollectRep.Code.UN_REACHABLE) {
                // UN_REACHABLE Peer unreachable (Network layer icmp)
                // UN_REACHABLE 对端不可达(网络层icmp)
                handlerMonitorAvailableAlert(monitorId, app, metricsData.getCode());
            } else if (metricsData.getCode() == CollectRep.Code.UN_CONNECTABLE) {
                // UN_CONNECTABLE Peer connection failure (transport layer tcp,udp)
                // UN_CONNECTABLE 对端连接失败(传输层tcp,udp)
                handlerMonitorAvailableAlert(monitorId, app, metricsData.getCode());
            } else {
                // Other exceptions
                // 其他异常
                handlerMonitorAvailableAlert(monitorId, app, metricsData.getCode());
            }
            return;
        } else {
            // Check whether an availability or unreachable alarm is generated before the association monitoring, and send a clear alarm to clear the monitoring status
            // 判断关联监控之前是否有可用性或者不可达告警,发送恢复告警进行监控状态恢复
            triggeredAlertMap.remove(String.valueOf(monitorId));
            boolean isRestartUnavailable = unAvailableMonitors.remove(monitorId);
            if (isRestartUnavailable) {
                // Sending an alarm Restore
                Map<String, String> tags = new HashMap<>(6);
                tags.put(CommonConstants.TAG_MONITOR_ID, String.valueOf(monitorId));
                tags.put(CommonConstants.TAG_MONITOR_APP, app);
                String content = this.bundle.getString("alerter.availability.resolved");
                long currentTimeMilli = System.currentTimeMillis();
                Alert resumeAlert = Alert.builder()
                        .tags(tags)
                        .target(CommonConstants.AVAILABILITY)
                        .content(content)
                        .priority(CommonConstants.ALERT_PRIORITY_CODE_WARNING)
                        .status(CommonConstants.ALERT_STATUS_CODE_RESTORED)
                        .firstAlarmTime(currentTimeMilli)
                        .lastAlarmTime(currentTimeMilli)
                        .triggerTimes(1)
                        .build();
                alarmCommonReduce.reduceAndSendAlarm(resumeAlert);
                Runnable updateStatusJob = () -> {
                    updateAvailabilityAlertStatus(monitorId, resumeAlert);
                };
                workerPool.executeJob(updateStatusJob);
            }
        }
    }

    private void handlerMonitorAvailableAlert(long monitorId, String app, CollectRep.Code code) {
        AlertDefine avaAlertDefine = alertDefineService.getMonitorBindAlertAvaDefine(monitorId, app, CommonConstants.AVAILABILITY);
        if (avaAlertDefine == null) {
            return;
        }
        Alert preAlert = triggeredAlertMap.get(String.valueOf(monitorId));
        long currentTimeMill = System.currentTimeMillis();
        Map<String, String> tags = new HashMap<>(6);
        tags.put(CommonConstants.TAG_MONITOR_ID, String.valueOf(monitorId));
        tags.put(CommonConstants.TAG_MONITOR_APP, app);
        tags.put("metrics", CommonConstants.AVAILABILITY);
        tags.put("code", code.name());
        Map<String, Object> valueMap = tags.entrySet()
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (avaAlertDefine.getTags() != null && !avaAlertDefine.getTags().isEmpty()) {
            for (TagItem tagItem : avaAlertDefine.getTags()) {
                valueMap.put(tagItem.getName(), tagItem.getValue());
                tags.put(tagItem.getName(), tagItem.getValue());
            }
        }
        if (preAlert == null) {
            Alert.AlertBuilder alertBuilder = Alert.builder()
                    .tags(tags)
                    .priority(CommonConstants.ALERT_PRIORITY_CODE_EMERGENCY)
                    .status(ALERT_STATUS_CODE_PENDING)
                    .target(CommonConstants.AVAILABILITY)
                    .content(AlertTemplateUtil.render(avaAlertDefine.getTemplate(), valueMap))
                    .firstAlarmTime(currentTimeMill)
                    .lastAlarmTime(currentTimeMill)
                    .triggerTimes(1);
            if (avaAlertDefine.getTimes() == null || avaAlertDefine.getTimes() <= 1) {
                alarmCommonReduce.reduceAndSendAlarm(alertBuilder.build().clone());
                unAvailableMonitors.add(monitorId);
            } else {
                alertBuilder.status(CommonConstants.ALERT_STATUS_CODE_NOT_REACH);
            }
            triggeredAlertMap.put(String.valueOf(monitorId), alertBuilder.build());
        } else {
            int times = preAlert.getTriggerTimes() + 1;
            if (preAlert.getStatus() == ALERT_STATUS_CODE_PENDING) {
                times = 1;
                preAlert.setContent(AlertTemplateUtil.render(avaAlertDefine.getTemplate(), valueMap));
                preAlert.setTags(tags);
            }
            preAlert.setTriggerTimes(times);
            preAlert.setFirstAlarmTime(currentTimeMill);
            preAlert.setLastAlarmTime(currentTimeMill);
            int defineTimes = avaAlertDefine.getTimes() == null ? 1 : avaAlertDefine.getTimes();
            if (times >= defineTimes) {
                preAlert.setStatus(ALERT_STATUS_CODE_PENDING);
                alarmCommonReduce.reduceAndSendAlarm(preAlert.clone());
                unAvailableMonitors.add(monitorId);
            } else {
                preAlert.setStatus(CommonConstants.ALERT_STATUS_CODE_NOT_REACH);
            }
        }
    }


    private void updateAvailabilityAlertStatus(long monitorId, Alert restoreAlert) {
        List<Alert> availabilityAlerts = queryAvailabilityAlerts(monitorId, restoreAlert);
        availabilityAlerts.stream().parallel().forEach(alert -> {
            log.info("updating alert id:{}",alert.getId());
            alertService.editAlertStatus(ALERT_STATUS_CODE_SOLVED, List.of(alert.getId()));
        });
    }

    private List<Alert> queryAvailabilityAlerts(long monitorId, Alert restoreAlert) {
        //create query condition
        Specification<Alert> specification = (root, query, criteriaBuilder) -> {
            List<Predicate> andList = new ArrayList<>();

            Predicate predicateTags = criteriaBuilder.like(root.get("tags").as(String.class), "%" + monitorId + "%");
            andList.add(predicateTags);

            Predicate predicatePriority = criteriaBuilder.equal(root.get("priority"), CommonConstants.ALERT_PRIORITY_CODE_EMERGENCY);
            andList.add(predicatePriority);

            Predicate predicateStatus = criteriaBuilder.equal(root.get("status"), ALERT_STATUS_CODE_PENDING);
            andList.add(predicateStatus);

            Predicate predicateAlertTime = criteriaBuilder.lessThanOrEqualTo(root.get("lastAlarmTime"), restoreAlert.getLastAlarmTime());
            andList.add(predicateAlertTime);

            Predicate[] predicates = new Predicate[andList.size()];
            return criteriaBuilder.and(andList.toArray(predicates));
        };

        //query results
        return alertService.getAlerts(specification);
    }
    
    @EventListener(SystemConfigChangeEvent.class)
    public void onEvent(SystemConfigChangeEvent event) {
        log.info("calculate alarm receive system config change event: {}.", event.getSource());
        this.bundle = ResourceBundleUtil.getBundle("alerter");
    }
}
