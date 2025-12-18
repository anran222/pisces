# Pisces - AI赋能的A/B测试实验系统

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Pisces是一个企业级A/B测试实验系统，通过AI技术赋能，实现从"人工试错"到"智能闭环"的跨越。系统支持多臂老虎机算法、贝叶斯统计分析、因果推断、异质处理效应分析等先进技术，大幅提升实验效率和优化效果。

## ✨ 核心特性

### 🎯 传统A/B测试功能
- **实验全生命周期管理**：创建、启动、暂停、停止、删除实验
- **灵活的流量分配**：支持随机、哈希、规则等多种分配策略
- **实时数据收集**：支持多种事件类型（浏览、点击、转化等）
- **统计分析**：实验组对比、转化率计算、显著性检验
- **配置管理**：基于Zookeeper的实时配置下发和版本管理

### 🤖 AI赋能功能

#### 1. 多臂老虎机算法（MAB）流量分配
- **Thompson Sampling（汤普森采样）**：基于贝叶斯统计的动态流量分配，快速收敛到最优变体
- **UCB（置信区间上界）**：平衡探索与利用，充分挖掘潜在最优解
- **实时奖励更新**：根据访客行为实时调整流量分配比例
- **效果提升**：实验周期缩短50%-60%，资源浪费降低60%-70%

#### 2. 贝叶斯统计分析
- **实时胜率计算**：实时计算变体击败基准的概率，无需等待预设样本量
- **实验提前终止**：当胜率≥95%时可提前终止实验，节省资源
- **决策更及时**：实验收敛速度提升40%-50%
- **结果更直观**：胜率直接表示"变体优于基准的概率"，易于理解

#### 3. 因果推断分析
- **双重差分法（DID）**：适用于存在时间趋势混淆的场景（如节假日、平台促销）
- **倾向得分匹配（PSM）**：适用于存在观测混淆变量的场景（如访客画像差异）
- **因果森林（Causal Forest）**：可同时估计平均处理效应（ATE）和条件平均处理效应（CATE）
- **精准归因**：剥离混淆变量影响，确保结论可信

#### 4. 异质处理效应（HTE）分析
- **群体差异化分析**：识别不同访客群体对策略的差异化反应
- **敏感群体识别**：自动划分高敏感、中敏感、低敏感访客群体
- **个性化策略**：为不同敏感群体推送对应的最优变体
- **个体处理效应（ITE）**：支持访客级别的处理效应估计

#### 5. 变体生成服务（框架）
- **文本变体生成**：使用生成式AI批量生成商品标题、详情页文案等
- **图像变体生成**：使用图像生成AI生成商品主图、详情页配图等
- **智能筛选机制**：二级筛选（规则过滤+算法预评估）确保变体质量
- **效果预评估**：使用预测模型评估变体的优化潜力

## 🏗️ 项目结构

本项目采用多模块Maven项目结构，分为三层架构：

```
pisces/
├── pom.xml                         # 父POM文件
├── pisces-common/                  # Common模块 - 存放DTO和模型
│   ├── pom.xml
│   └── src/main/java/com/pisces/common/
│       ├── model/                  # 数据模型
│       │   ├── Experiment.java
│       │   ├── ExperimentGroup.java
│       │   ├── TrafficConfig.java
│       │   └── ...
│       ├── request/                # 请求DTO
│       │   ├── ExperimentCreateRequest.java
│       │   ├── EventReportRequest.java
│       │   └── ...
│       ├── response/               # 响应DTO
│       │   ├── BaseResponse.java
│       │   ├── ExperimentResponse.java
│       │   └── ...
│       └── enums/                  # 枚举类
│           └── ResponseCode.java
├── pisces-service/                 # Service模块 - 业务逻辑处理
│   ├── pom.xml
│   └── src/main/java/com/pisces/service/
│       ├── PiscesApplication.java  # Spring Boot 主应用类
│       ├── service/                # 服务接口和实现
│       │   ├── ExperimentService.java
│       │   ├── TrafficService.java
│       │   ├── DataService.java
│       │   ├── AnalysisService.java
│       │   ├── MultiArmedBanditService.java      # MAB算法服务
│       │   ├── BayesianAnalysisService.java      # 贝叶斯分析服务
│       │   ├── CausalInferenceService.java       # 因果推断服务
│       │   ├── HTEAnalysisService.java           # HTE分析服务
│       │   ├── VariantGenerationService.java     # 变体生成服务
│       │   └── impl/              # 服务实现
│       ├── config/                 # 配置类
│       ├── exception/             # 异常处理
│       ├── aspect/                # 切面处理
│       └── zookeeper/             # Zookeeper客户端
└── pisces-api/                     # API模块 - REST接口
    ├── pom.xml
    └── src/main/java/com/pisces/api/
        ├── experiment/            # 实验管理接口
        ├── traffic/               # 流量分配接口
        ├── data/                  # 数据上报接口
        ├── analysis/              # 数据分析接口
        └── variant/              # 变体生成接口
```

## 🛠️ 技术栈

### 核心框架
- **Spring Boot 3.2.0** - 应用框架
- **Java 21** - 编程语言
- **Maven** - 项目管理和构建工具

### 数据存储
- **Redis 6.0+** - 缓存（可选，用于提升性能）
- **Zookeeper 3.9+** - 分布式配置管理（可选，也可使用内存存储）
- **内存存储** - 默认使用内存存储，无需数据库

### 工具库
- **Lombok** - 简化Java代码

### AI/ML技术（框架支持）
- **多臂老虎机算法** - Thompson Sampling、UCB
- **贝叶斯统计** - 实时胜率计算
- **因果推断** - DID、PSM、因果森林
- **生成式AI** - 变体生成（需集成外部服务）

## 🚀 快速开始

### 环境要求

- **JDK 21+**
- **Maven 3.6+**
- **Redis 6.0+** (可选，用于缓存，提升性能)
- **Zookeeper 3.9+** (可选，用于配置管理，也可使用内存存储)

**注意**：系统完全支持无依赖运行，所有数据存储在内存中。Redis和Zookeeper都是可选的。

### 1. 克隆项目

```bash
git clone <repository-url>
cd pisces
```

### 2. 配置应用（可选）

如果需要使用Redis或Zookeeper，编辑 `pisces-service/src/main/resources/application.yml`:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password:  # Redis密码，如果没有密码则留空
      database: 0

zookeeper:
  connectString: localhost:2181
  sessionTimeoutMs: 30000
  connectionTimeoutMs: 30000
  maxRetries: 3
  basePath: /pisces
```

**注意**：系统支持完全无依赖运行，所有数据存储在内存中。Redis和Zookeeper都是可选的。

### 3. 编译和运行

```bash
# 编译整个项目
mvn clean compile

# 运行项目
cd pisces-service
mvn spring-boot:run

# 或在项目根目录运行
mvn spring-boot:run -pl pisces-service
```

项目启动后，访问地址：`http://localhost:8080/api`

## 📖 快速使用

### 使用SDK（推荐）

**JavaScript SDK**：
```javascript
const pisces = new PiscesSDK({
  apiBaseUrl: 'http://localhost:8080/api',
  experimentId: 'exp_price_001',
  visitorId: getVisitorId()
});

// 获取实验组
const groupId = await pisces.getGroup();

// 上报事件
await pisces.reportTransaction({
  transactionPrice: 4800  // 核心指标
});
```

**Java SDK**：
```java
PiscesClient client = new PiscesClient("http://localhost:8080/api");
String groupId = client.assignGroup("exp_price_001", visitorId);
client.reportTransaction("exp_price_001", visitorId, transactionData);
```

### 直接调用API

**分配访客到实验组**：
```bash
POST /api/traffic/assign
Content-Type: application/json

{
  "experimentId": "exp_price_001",
  "visitorId": "visitor_12345"
}
```

**上报事件**：
```bash
POST /api/data/event
Content-Type: application/json

{
  "experimentId": "exp_price_001",
  "visitorId": "visitor_12345",
  "eventType": "CONVERT",
  "eventName": "transaction_completed",
  "properties": {
    "transactionPrice": 4800
  }
}
```

**查看分析结果**：
```bash
GET /api/analysis/experiment/exp_price_001/statistics
GET /api/analysis/experiment/exp_price_001/bayesian
```


## 📚 文档

### 核心文档
- **[完整实施指南](COMPLETE_GUIDE.md)** ⭐ - **一站式完整指南**：包含实验设计、SDK使用、后端集成、前端集成、代码示例等所有内容
- **[SDK使用指南](SDK_README.md)** - JavaScript和Java SDK的快速使用说明

## 📝 许可证

MIT License

## 🤝 贡献

欢迎提交Issue和Pull Request！

## 📧 联系方式

如有问题或建议，请通过Issue反馈。

---

**Pisces** - 让A/B测试更智能、更高效！
