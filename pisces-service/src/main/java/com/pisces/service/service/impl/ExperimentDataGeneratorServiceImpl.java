package com.pisces.service.service.impl;

import com.pisces.common.model.Experiment;
import com.pisces.common.request.ExperimentCreateRequest;
import com.pisces.service.service.DataService;
import com.pisces.service.service.ExperimentDataGeneratorService;
import com.pisces.service.service.ExperimentService;
import com.pisces.service.service.TrafficService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 实验数据生成服务实现
 */
@Slf4j
@Service
public class ExperimentDataGeneratorServiceImpl implements ExperimentDataGeneratorService {
    
    @Autowired
    private ExperimentService experimentService;
    
    @Autowired
    private TrafficService trafficService;
    
    @Autowired
    private DataService dataService;
    
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
        generateEventData(experimentId, groupVisitors);
        
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
        Map<String, List<String>> groupVisitors = new HashMap<>();
        groupVisitors.put("A", new ArrayList<>());
        groupVisitors.put("B", new ArrayList<>());
        groupVisitors.put("C", new ArrayList<>());
        groupVisitors.put("D", new ArrayList<>());
        
        Random random = new Random();
        int totalVisitors = visitorCountPerGroup * 4;
        
        for (int i = 1; i <= totalVisitors; i++) {
            String visitorId = "visitor_" + String.format("%05d", i);
            
            // 分配访客到实验组（系统会根据流量分配策略自动分配）
            String groupId = trafficService.assignGroup(experimentId, visitorId);
            
            if (groupId != null && groupVisitors.containsKey(groupId)) {
                groupVisitors.get(groupId).add(visitorId);
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
    private void generateEventData(String experimentId, Map<String, List<String>> groupVisitors) {
        Random random = new Random();
        LocalDateTime baseTime = LocalDateTime.now().minusDays(7); // 从7天前开始
        
        // 不同组的转化率和价格提升效果
        Map<String, GroupStats> groupStats = new HashMap<>();
        groupStats.put("A", new GroupStats(0.10, 4500, 0.75));   // 基准组：10%转化率，4500元，75%市场价
        groupStats.put("B", new GroupStats(0.11, 4650, 0.775));  // 变体1：11%转化率，4650元，77.5%市场价
        groupStats.put("C", new GroupStats(0.105, 4725, 0.7875)); // 变体2：10.5%转化率，4725元，78.75%市场价
        groupStats.put("D", new GroupStats(0.12, 4800, 0.80));    // 变体3：12%转化率，4800元，80%市场价
        
        for (Map.Entry<String, List<String>> entry : groupVisitors.entrySet()) {
            String groupId = entry.getKey();
            List<String> visitors = entry.getValue();
            GroupStats stats = groupStats.get(groupId);
            
            for (String visitorId : visitors) {
                // 生成VIEW事件（所有访客都会浏览）
                generateViewEvent(experimentId, visitorId, groupId, baseTime, random);
                
                // 根据转化率决定是否点击和转化
                // 点击率约为转化率的5倍（例如：10%转化率 → 50%点击率）
                double clickRate = stats.conversionRate * 5;
                if (random.nextDouble() < clickRate) {
                    generateClickEvent(experimentId, visitorId, groupId, baseTime, random);
                    
                    // 根据转化率决定是否转化
                    // 在已点击的访客中，按转化率决定是否转化
                    if (random.nextDouble() < (stats.conversionRate / clickRate)) {
                        generateConvertEvent(experimentId, visitorId, groupId, baseTime, random, stats);
                    }
                }
            }
        }
        
        log.info("事件数据生成完成: 实验ID={}", experimentId);
    }
    
    private void generateViewEvent(String experimentId, String visitorId, String groupId, 
                                   LocalDateTime baseTime, Random random) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("productId", "product_" + String.format("%03d", random.nextInt(100)));
        properties.put("productPrice", 4500 + random.nextInt(500));
        properties.put("marketPrice", 6000);
        properties.put("productModel", "iPhone 13 Pro");
        properties.put("condition", getRandomCondition(random));
        
        dataService.reportEvent(experimentId, visitorId, "VIEW", "product_detail_view", properties);
    }
    
    private void generateClickEvent(String experimentId, String visitorId, String groupId, 
                                    LocalDateTime baseTime, Random random) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("productId", "product_" + String.format("%03d", random.nextInt(100)));
        properties.put("productPrice", 4500 + random.nextInt(500));
        
        dataService.reportEvent(experimentId, visitorId, "CLICK", "contact_seller", properties);
    }
    
    private void generateConvertEvent(String experimentId, String visitorId, String groupId, 
                                      LocalDateTime baseTime, Random random, GroupStats stats) {
        // 在基准价格基础上添加随机波动
        int priceVariation = random.nextInt(300) - 150; // -150到+150的波动
        int transactionPrice = stats.basePrice + priceVariation;
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("productId", "product_" + String.format("%03d", random.nextInt(100)));
        properties.put("transactionPrice", transactionPrice);
        properties.put("listPrice", 4500);
        properties.put("marketPrice", 6000);
        properties.put("priceRatio", (double) transactionPrice / 6000);
        properties.put("transactionDate", baseTime.plusDays(random.nextInt(7))
                .plusHours(random.nextInt(24))
                .plusMinutes(random.nextInt(60)));
        
        dataService.reportEvent(experimentId, visitorId, "CONVERT", "transaction_completed", properties);
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
}
