package com.pisces.service.service.impl;

import com.pisces.common.model.Experiment;
import com.pisces.common.model.EventDefinition;
import com.pisces.common.model.ExperimentMetadata;
import com.pisces.common.model.GroupConfigFieldDefinition;
import com.pisces.common.model.MetricDefinition;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.service.exception.BusinessException;
import com.pisces.common.enums.ResponseCode;
import com.pisces.service.security.ApiKeyContextHolder;
import com.pisces.service.service.ConfigService;
import com.pisces.service.service.DataService;
import com.pisces.service.service.ExperimentDataGeneratorService;
import com.pisces.service.service.ExperimentService;
import com.pisces.service.service.TrafficService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.Locale;

/**
 * 实验数据生成服务实现
 */
@Slf4j
@Service
public class ExperimentDataGeneratorServiceImpl implements ExperimentDataGeneratorService {

    private static final String DEFAULT_VIEW_EVENT = "VIEW";
    private static final String DEFAULT_CLICK_EVENT = "CLICK";
    private static final String DEFAULT_CONVERT_EVENT = "CONVERT";
    private static final String PRODUCT_VIEW_EVENT = "PRODUCT_VIEW";
    private static final String CONSULT_CLICK_EVENT = "CONSULT_CLICK";
    private static final String PAY_SUCCESS_EVENT = "PAY_SUCCESS";
    private static final String PAYMENT_RATE_METRIC = "PAYMENT_RATE";
    private static final String CONSULT_RATE_METRIC = "CONSULT_RATE";
    
    @Autowired
    private ExperimentService experimentService;
    
    @Autowired
    private TrafficService trafficService;
    
    @Autowired
    private DataService dataService;

    @Autowired
    private ConfigService configService;
    
    /**
     * 生成完整的实验流程数据
     * 
     * @param experimentName 实验名称
     * @param visitorCount 访客数量（每个实验组）
     * @param daysAgo 实验开始时间（几天前）
     * @return 生成的实验ID
     */
    @Override
    public String generateCompleteExperimentData(String experimentName, int visitorCount, int daysAgo) {
        log.info("开始生成完整实验数据: 实验名称={}, 访客数={}, 开始时间={}天前", 
                experimentName, visitorCount, daysAgo);
        
        // 1. 创建实验
        String experimentId = createAndStartExperiment(experimentName, daysAgo);
        
        // 2. 分配访客到实验组
        Map<String, List<String>> groupVisitors = assignVisitorsToGroups(experimentId, visitorCount);
        
        // 3. 生成事件数据
        generateEventData(experimentId, groupVisitors, Math.max(1, daysAgo), generatedExperimentSimulationProfile());
        
        log.info("实验数据生成完成: 实验ID={}, 总访客数={}", 
                experimentId, visitorCount * 4); // 4个实验组
        
        return experimentId;
    }
    
    /**
     * 快速生成默认实验数据（使用默认参数）
     * 
     * @return 生成的实验ID
     */
    @Override
    public String generateDefaultExperimentData() {
        return generateCompleteExperimentData(
                "二手手机交易价格提升实验（自动生成）", 
                100,  // 每个组100个访客
                7     // 7天前开始
        );
    }
    
    /**
     * 快速生成实验数据（使用推荐参数）
     * 
     * @return 生成的实验ID
     */
    @Override
    public String generateQuickExperimentData() {
        return generateCompleteExperimentData(
                "二手手机交易价格提升实验",
                200,  // 每个组200个访客，总800个访客
                14    // 14天前开始
        );
    }

    @Override
    public void generateDataForExistingExperiment(String experimentId, int visitorCountPerGroup, int daysSpan) {
        ExperimentMetadata metadata = configService.getExperimentConfig(experimentId);
        if (metadata == null || metadata.getGroups() == null || metadata.getGroups().isEmpty()) {
            throw new BusinessException(ResponseCode.EXPERIMENT_NOT_FOUND, "实验不存在或未配置实验组");
        }
        ApiKeyContextHolder.assertCanAccess(metadata);

        Map<String, List<String>> groupVisitors = assignVisitorsToConfiguredGroups(
                experimentId,
                new ArrayList<>(new TreeSet<>(metadata.getGroups().keySet())),
                visitorCountPerGroup
        );
        generateEventData(experimentId, groupVisitors, Math.max(1, daysSpan), resolveSimulationProfile(metadata));
    }
    
    /**
     * 创建并启动实验
     */
    private String createAndStartExperiment(String experimentName, int daysAgo) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startTime = now.minusDays(daysAgo);
        LocalDateTime endTime = now.plusDays(14); // 实验持续14天
        
        ExperimentCreateRequest request = new ExperimentCreateRequest();
        request.setName(experimentName);
        request.setDescription("自动生成的实验数据 - " + experimentName);
        request.setStartTime(startTime);
        request.setEndTime(endTime);
        
        // 配置实验组
        List<ExperimentCreateRequest.GroupConfig> groups = new ArrayList<>();
        
        // 基准组A
        Map<String, Object> configA = new HashMap<>();
        configA.put("titleTemplate", "{成色} {型号}");
        configA.put("showMarketPrice", false);
        configA.put("showQualityReport", false);
        configA.put("trustElements", Arrays.asList("sellerCredit"));
        
        ExperimentCreateRequest.GroupConfig groupA = new ExperimentCreateRequest.GroupConfig();
        groupA.setId("A");
        groupA.setName("基准组-当前版本");
        groupA.setTrafficRatio(0.25);
        groupA.setConfig(configA);
        groups.add(groupA);
        
        // 变体组B
        Map<String, Object> configB = new HashMap<>();
        configB.put("titleTemplate", "{成色} {型号} 官方质检 无拆修");
        configB.put("showMarketPrice", false);
        configB.put("showQualityReport", true);
        configB.put("trustElements", Arrays.asList("sellerCredit", "qualityReport", "noRepair"));
        
        ExperimentCreateRequest.GroupConfig groupB = new ExperimentCreateRequest.GroupConfig();
        groupB.setId("B");
        groupB.setName("变体1-突出信任要素");
        groupB.setTrafficRatio(0.25);
        groupB.setConfig(configB);
        groups.add(groupB);
        
        // 变体组C
        Map<String, Object> configC = new HashMap<>();
        configC.put("titleTemplate", "{成色} {型号}");
        configC.put("showMarketPrice", true);
        configC.put("showQualityReport", false);
        configC.put("trustElements", Arrays.asList("sellerCredit"));
        
        ExperimentCreateRequest.GroupConfig groupC = new ExperimentCreateRequest.GroupConfig();
        groupC.setId("C");
        groupC.setName("变体2-价格锚定");
        groupC.setTrafficRatio(0.25);
        groupC.setConfig(configC);
        groups.add(groupC);
        
        // 变体组D
        Map<String, Object> configD = new HashMap<>();
        configD.put("titleTemplate", "{成色} {型号} 官方质检 无拆修");
        configD.put("showMarketPrice", true);
        configD.put("showQualityReport", true);
        configD.put("trustElements", Arrays.asList("sellerCredit", "qualityReport", "noRepair"));
        
        ExperimentCreateRequest.GroupConfig groupD = new ExperimentCreateRequest.GroupConfig();
        groupD.setId("D");
        groupD.setName("变体3-组合策略");
        groupD.setTrafficRatio(0.25);
        groupD.setConfig(configD);
        groups.add(groupD);
        
        request.setGroups(groups);
        
        // 配置流量分配
        ExperimentCreateRequest.TrafficConfigRequest traffic = new ExperimentCreateRequest.TrafficConfigRequest();
        traffic.setTotalTraffic(1.0);
        traffic.setStrategy("THOMPSON_SAMPLING");
        
        List<ExperimentCreateRequest.GroupAllocationRequest> allocations = new ArrayList<>();
        allocations.add(createAllocation("A", 0.25));
        allocations.add(createAllocation("B", 0.25));
        allocations.add(createAllocation("C", 0.25));
        allocations.add(createAllocation("D", 0.25));
        traffic.setAllocation(allocations);
        
        request.setTraffic(traffic);
        request.setWhitelist(new ArrayList<>());
        request.setBlacklist(new ArrayList<>());
        request.setEventDefinitions(defaultEventDefinitions());
        request.setMetricDefinitions(defaultMetricDefinitions());
        request.setGroupConfigSchema(defaultGroupConfigSchema());
        
        // 创建实验
        Experiment experiment = experimentService.createExperiment(request);
        String experimentId = experiment.getId();
        
        // 启动实验
        experimentService.startExperiment(experimentId);
        
        log.info("实验创建并启动成功: 实验ID={}", experimentId);
        
        return experimentId;
    }
    
    private ExperimentCreateRequest.GroupAllocationRequest createAllocation(String group, double ratio) {
        ExperimentCreateRequest.GroupAllocationRequest allocation = 
                new ExperimentCreateRequest.GroupAllocationRequest();
        allocation.setGroup(group);
        allocation.setRatio(ratio);
        return allocation;
    }
    
    /**
     * 分配访客到实验组
     */
    private Map<String, List<String>> assignVisitorsToGroups(String experimentId, int visitorCountPerGroup) {
        return assignVisitorsToConfiguredGroups(
                experimentId,
                List.of("A", "B", "C", "D"),
                visitorCountPerGroup
        );
    }

    private Map<String, List<String>> assignVisitorsToConfiguredGroups(
            String experimentId,
            List<String> groupIds,
            int visitorCountPerGroup
    ) {
        Map<String, List<String>> groupVisitors = new LinkedHashMap<>();
        for (String groupId : groupIds) {
            groupVisitors.put(groupId, new ArrayList<>());
        }

        int visitorSequence = 1;
        int maxAttempts = Math.max(1000, groupIds.size() * visitorCountPerGroup * 20);
        int attempts = 0;

        while (groupVisitors.values().stream().anyMatch(visitors -> visitors.size() < visitorCountPerGroup)
                && attempts < maxAttempts) {
            String visitorId = "visitor_" + String.format("%06d", visitorSequence++);
            String groupId = trafficService.assignGroup(experimentId, visitorId);
            attempts++;

            if (groupId == null || !groupVisitors.containsKey(groupId)) {
                continue;
            }

            List<String> visitors = groupVisitors.get(groupId);
            if (visitors.size() < visitorCountPerGroup) {
                visitors.add(visitorId);
            }
        }

        log.info("访客分配完成: 实验ID={}, 各组访客数={}", 
                experimentId, groupVisitors.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue().size())));
        
        return groupVisitors;
    }
    
    /**
     * 生成事件数据
     */
    private void generateEventData(String experimentId, Map<String, List<String>> groupVisitors, int daysSpan,
                                   EventSimulationProfile simulationProfile) {
        Random random = new Random();
        LocalDateTime baseTime = LocalDateTime.now().minusDays(daysSpan); // 从指定天数前开始
        
        int groupIndex = 0;
        for (Map.Entry<String, List<String>> entry : groupVisitors.entrySet()) {
            String groupId = entry.getKey();
            List<String> visitors = entry.getValue();
            GroupStats stats = resolveGroupStats(groupId, groupIndex++);
            
            for (String visitorId : visitors) {
                generateViewEvent(experimentId, visitorId, groupId, baseTime, random, daysSpan, simulationProfile);
                
                // 根据转化率决定是否点击和转化
                // 点击率约为转化率的5倍（例如：10%转化率 → 50%点击率）
                double clickRate = stats.conversionRate * 5;
                if (random.nextDouble() < clickRate) {
                    generateClickEvent(experimentId, visitorId, groupId, baseTime, random, daysSpan, simulationProfile);
                    
                    // 根据转化率决定是否转化
                    // 在已点击的访客中，按转化率决定是否转化
                    if (random.nextDouble() < (stats.conversionRate / clickRate)) {
                        generateConvertEvent(experimentId, visitorId, groupId, baseTime, random, stats, daysSpan, simulationProfile);
                    }
                }
            }
        }
        
        log.info("事件数据生成完成: 实验ID={}", experimentId);
    }

    private GroupStats resolveGroupStats(String groupId, int groupIndex) {
        String normalizedGroupId = groupId == null ? "" : groupId.toLowerCase(Locale.ROOT);
        return switch (normalizedGroupId) {
            case "a" -> new GroupStats(0.10, 4500, 0.75);
            case "b" -> new GroupStats(0.11, 4650, 0.775);
            case "c" -> new GroupStats(0.105, 4725, 0.7875);
            case "d" -> new GroupStats(0.12, 4800, 0.80);
            case "control" -> new GroupStats(0.09, 5600, 0.80);
            default -> resolveConfiguredGroupStats(normalizedGroupId, groupIndex);
        };
    }

    private GroupStats resolveConfiguredGroupStats(String normalizedGroupId, int groupIndex) {
        if (normalizedGroupId.contains("trust")) {
            return new GroupStats(0.14, 5800, 0.83);
        }
        return groupIndex == 0
                ? new GroupStats(0.10, 4500, 0.75)
                : new GroupStats(0.12, 4800, 0.80);
    }
    
    private void generateViewEvent(String experimentId, String visitorId, String groupId,
                                   LocalDateTime baseTime, Random random, int daysSpan,
                                   EventSimulationProfile simulationProfile) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("productId", "product_" + String.format("%03d", random.nextInt(100)));
        properties.put("productPrice", 4500 + random.nextInt(500));
        properties.put("marketPrice", 6000);
        properties.put("productModel", "iPhone 13 Pro");
        properties.put("condition", getRandomCondition(random));
        properties.put("eventTime", randomEventTime(baseTime, random, daysSpan));
        
        dataService.reportEvent(experimentId, visitorId,
                simulationProfile.getViewEventType(), simulationProfile.getViewEventName(), properties);
    }
    
    private void generateClickEvent(String experimentId, String visitorId, String groupId,
                                    LocalDateTime baseTime, Random random, int daysSpan,
                                    EventSimulationProfile simulationProfile) {
        if (simulationProfile.getClickEventType() == null) {
            return;
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put("productId", "product_" + String.format("%03d", random.nextInt(100)));
        properties.put("productPrice", 4500 + random.nextInt(500));
        properties.put("eventTime", randomEventTime(baseTime, random, daysSpan));
        
        dataService.reportEvent(experimentId, visitorId,
                simulationProfile.getClickEventType(), simulationProfile.getClickEventName(), properties);
    }
    
    private void generateConvertEvent(String experimentId, String visitorId, String groupId,
                                      LocalDateTime baseTime, Random random, GroupStats stats, int daysSpan,
                                      EventSimulationProfile simulationProfile) {
        // 在基准价格基础上添加随机波动
        int priceVariation = random.nextInt(300) - 150; // -150到+150的波动
        int transactionPrice = stats.basePrice + priceVariation;
        LocalDateTime eventTime = randomEventTime(baseTime, random, daysSpan);
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("productId", "product_" + String.format("%03d", random.nextInt(100)));
        properties.put("transactionPrice", transactionPrice);
        properties.put("listPrice", 4500);
        properties.put("marketPrice", 6000);
        properties.put("priceRatio", (double) transactionPrice / 6000);
        properties.put("transactionDate", eventTime);
        properties.put("eventTime", eventTime);
        
        dataService.reportEvent(experimentId, visitorId,
                simulationProfile.getConvertEventType(), simulationProfile.getConvertEventName(), properties);
    }

    private List<EventDefinition> defaultEventDefinitions() {
        return List.of(
                eventDefinition(PRODUCT_VIEW_EVENT, "商品查看", true),
                eventDefinition(CONSULT_CLICK_EVENT, "咨询点击", false),
                eventDefinition(PAY_SUCCESS_EVENT, "支付成功", false)
        );
    }

    private List<MetricDefinition> defaultMetricDefinitions() {
        return List.of(
                rateMetric(PAYMENT_RATE_METRIC, "支付率", PAY_SUCCESS_EVENT, PRODUCT_VIEW_EVENT, true, false),
                rateMetric(CONSULT_RATE_METRIC, "咨询率", CONSULT_CLICK_EVENT, PRODUCT_VIEW_EVENT, false, true)
        );
    }

    private List<GroupConfigFieldDefinition> defaultGroupConfigSchema() {
        return List.of(
                schemaField("titleTemplate", "标题模板", "STRING", true, "实验组标题模板"),
                schemaField("showMarketPrice", "展示市场价", "BOOLEAN", false, "是否展示市场价锚点"),
                schemaField("showQualityReport", "展示质检报告", "BOOLEAN", false, "是否展示官方质检报告"),
                schemaField("trustElements", "信任元素", "JSON", false, "商品卡展示的信任元素列表")
        );
    }

    private EventDefinition eventDefinition(String key, String label, boolean primary) {
        EventDefinition eventDefinition = new EventDefinition();
        eventDefinition.setKey(key);
        eventDefinition.setLabel(label);
        eventDefinition.setDescription(label + "事件");
        eventDefinition.setCategory("BUSINESS");
        eventDefinition.setPrimary(primary);
        return eventDefinition;
    }

    private MetricDefinition rateMetric(String key, String name, String numeratorEventType,
                                        String denominatorEventType, boolean primaryMetric,
                                        boolean guardrailMetric) {
        MetricDefinition metricDefinition = new MetricDefinition();
        metricDefinition.setKey(key);
        metricDefinition.setName(name);
        metricDefinition.setDescription(name + "（自动生成）");
        metricDefinition.setAggregationType(MetricDefinition.AggregationType.RATE);
        metricDefinition.setNumeratorEventType(numeratorEventType);
        metricDefinition.setDenominatorType(MetricDefinition.DenominatorType.EVENT_COUNT);
        metricDefinition.setDenominatorEventType(denominatorEventType);
        metricDefinition.setPrimaryMetric(primaryMetric);
        metricDefinition.setGuardrailMetric(guardrailMetric);
        return metricDefinition;
    }

    private GroupConfigFieldDefinition schemaField(String key, String label, String valueType,
                                                   boolean required, String description) {
        GroupConfigFieldDefinition fieldDefinition = new GroupConfigFieldDefinition();
        fieldDefinition.setKey(key);
        fieldDefinition.setLabel(label);
        fieldDefinition.setValueType(GroupConfigFieldDefinition.ValueType.ofOrThrow(valueType));
        fieldDefinition.setRequired(required);
        fieldDefinition.setDescription(description);
        return fieldDefinition;
    }

    private EventSimulationProfile defaultSimulationProfile() {
        return new EventSimulationProfile(DEFAULT_VIEW_EVENT, "product_detail_view",
                DEFAULT_CLICK_EVENT, "contact_seller",
                DEFAULT_CONVERT_EVENT, "transaction_completed");
    }

    private EventSimulationProfile generatedExperimentSimulationProfile() {
        return new EventSimulationProfile(PRODUCT_VIEW_EVENT, "product_detail_view",
                CONSULT_CLICK_EVENT, "contact_seller",
                PAY_SUCCESS_EVENT, "transaction_completed");
    }

    private EventSimulationProfile resolveSimulationProfile(ExperimentMetadata metadata) {
        if (metadata == null || metadata.getEventDefinitions() == null || metadata.getEventDefinitions().isEmpty()) {
            return defaultSimulationProfile();
        }

        List<EventDefinition> eventDefinitions = metadata.getEventDefinitions();
        List<MetricDefinition> metricDefinitions = metadata.getMetricDefinitions();
        MetricDefinition primaryMetric = metricDefinitions == null ? null : metricDefinitions.stream()
                .filter(metricDefinition -> Boolean.TRUE.equals(metricDefinition.getPrimaryMetric()))
                .findFirst()
                .orElse(metricDefinitions.isEmpty() ? null : metricDefinitions.getFirst());

        String viewEventType = resolveViewEventType(eventDefinitions, primaryMetric);
        String convertEventType = resolveConvertEventType(eventDefinitions, primaryMetric, viewEventType);
        String clickEventType = resolveClickEventType(eventDefinitions, viewEventType, convertEventType);

        return new EventSimulationProfile(
                viewEventType,
                defaultEventName(viewEventType),
                clickEventType,
                defaultEventName(clickEventType),
                convertEventType,
                defaultEventName(convertEventType)
        );
    }

    private String resolveViewEventType(List<EventDefinition> eventDefinitions, MetricDefinition primaryMetric) {
        if (primaryMetric != null
                && primaryMetric.getAggregationType() == MetricDefinition.AggregationType.RATE
                && primaryMetric.getDenominatorType() == MetricDefinition.DenominatorType.EVENT_COUNT
                && primaryMetric.getDenominatorEventType() != null) {
            return primaryMetric.getDenominatorEventType();
        }
        return eventDefinitions.stream()
                .filter(EventDefinition::getPrimary)
                .map(EventDefinition::getKey)
                .findFirst()
                .orElse(eventDefinitions.getFirst().getKey());
    }

    private String resolveConvertEventType(List<EventDefinition> eventDefinitions, MetricDefinition primaryMetric,
                                           String viewEventType) {
        if (primaryMetric != null && primaryMetric.getNumeratorEventType() != null) {
            return primaryMetric.getNumeratorEventType();
        }
        return eventDefinitions.stream()
                .map(EventDefinition::getKey)
                .filter(key -> !Objects.equals(key, viewEventType))
                .reduce((first, second) -> second)
                .orElse(viewEventType);
    }

    private String resolveClickEventType(List<EventDefinition> eventDefinitions, String viewEventType,
                                         String convertEventType) {
        return eventDefinitions.stream()
                .map(EventDefinition::getKey)
                .filter(key -> !Objects.equals(key, viewEventType))
                .filter(key -> !Objects.equals(key, convertEventType))
                .findFirst()
                .orElse(null);
    }

    private String defaultEventName(String eventType) {
        if (eventType == null) {
            return null;
        }
        return eventType.toLowerCase(Locale.ROOT);
    }

    private LocalDateTime randomEventTime(LocalDateTime baseTime, Random random, int daysSpan) {
        return baseTime
                .plusDays(random.nextInt(Math.max(1, daysSpan)))
                .plusHours(random.nextInt(24))
                .plusMinutes(random.nextInt(60));
    }
    
    private String getRandomCondition(Random random) {
        String[] conditions = {"95新", "9成新", "85新", "8成新"};
        return conditions[random.nextInt(conditions.length)];
    }
    
    /**
     * 实验组统计数据
     */
    private static class GroupStats {
        double conversionRate;  // 转化率
        int basePrice;          // 基准价格
        double priceRatio;      // 价格/市场价比例
        
        GroupStats(double conversionRate, int basePrice, double priceRatio) {
            this.conversionRate = conversionRate;
            this.basePrice = basePrice;
            this.priceRatio = priceRatio;
        }
    }

    private static class EventSimulationProfile {
        private final String viewEventType;
        private final String viewEventName;
        private final String clickEventType;
        private final String clickEventName;
        private final String convertEventType;
        private final String convertEventName;

        private EventSimulationProfile(String viewEventType, String viewEventName,
                                       String clickEventType, String clickEventName,
                                       String convertEventType, String convertEventName) {
            this.viewEventType = viewEventType;
            this.viewEventName = viewEventName;
            this.clickEventType = clickEventType;
            this.clickEventName = clickEventName;
            this.convertEventType = convertEventType;
            this.convertEventName = convertEventName;
        }

        private String getViewEventType() {
            return viewEventType;
        }

        private String getViewEventName() {
            return viewEventName;
        }

        private String getClickEventType() {
            return clickEventType;
        }

        private String getClickEventName() {
            return clickEventName;
        }

        private String getConvertEventType() {
            return convertEventType;
        }

        private String getConvertEventName() {
            return convertEventName;
        }
    }
}
