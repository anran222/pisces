# 模块地图

## `pisces-common`

主要放公共协议和领域模型。

关键文件：

- `pisces-common/src/main/java/com/pisces/common/model/Experiment.java`
- `pisces-common/src/main/java/com/pisces/common/model/ExperimentMetadata.java`
- `pisces-common/src/main/java/com/pisces/common/model/GroupConfigFieldDefinition.java`
- `pisces-common/src/main/java/com/pisces/common/model/Statistics.java`
- `pisces-common/src/main/java/com/pisces/common/request/ExperimentCreateRequest.java`
- `pisces-common/src/main/java/com/pisces/common/request/AIDesignRequest.java`
- `pisces-common/src/main/java/com/pisces/common/request/VariantCandidateGenerateRequest.java`
- `pisces-common/src/main/java/com/pisces/common/response/ExperimentResponse.java`
- `pisces-common/src/main/java/com/pisces/common/response/AIDesignResponse.java`
- `pisces-common/src/main/java/com/pisces/common/response/AIDiagnosisResponse.java`
- `pisces-common/src/main/java/com/pisces/common/response/AIGraduationDecisionResponse.java`

## `pisces-service`

### 实验主流程

- `service/impl/ExperimentServiceImpl.java`
- `schema/GroupConfigSchemaValidator.java`
- `service/impl/ConfigServiceImpl.java`

### 流量与算法

- `service/impl/TrafficServiceImpl.java`
- `service/impl/MultiArmedBanditServiceImpl.java`
- `service/impl/BayesianAnalysisServiceImpl.java`

### 数据与分析

- `service/impl/DataServiceImpl.java`
- `service/impl/AnalysisServiceImpl.java`
- `service/impl/CausalInferenceServiceImpl.java`
- `service/impl/HTEAnalysisServiceImpl.java`

### AI

- `service/impl/AIDecisionServiceImpl.java`
- `ai/ExperimentDecisionContextBuilder.java`
- `ai/PromptTemplateBuilder.java`
- `ai/AIDecisionJsonParser.java`
- `ai/DecisionGuardrailEvaluator.java`
- `ai/TongYiTextGenerationClient.java`

### 变体与演示

- `service/impl/VariantGenerationServiceImpl.java`
- `service/impl/ExperimentDemoServiceImpl.java`
- `service/impl/ExperimentDataGeneratorServiceImpl.java`

## `pisces-api`

### Controller

- `experiment/ExperimentController.java`
- `experiment/ExperimentDataGeneratorController.java`
- `traffic/TrafficController.java`
- `data/DataController.java`
- `analysis/AnalysisController.java`
- `variant/VariantController.java`

## SDK

### Java

- `pisces-sdk-java/src/main/java/com/pisces/sdk/PiscesClient.java`
- `pisces-sdk-java/src/main/java/com/pisces/sdk/model/ExperimentConfig.java`

### JS

- `pisces-sdk-js/pisces-sdk.js`

## 前端

前端仓库在 `../pisces-web`。

关键文件：

- `src/App.jsx`
- `src/services/api.js`
- `src/pages/Dashboard.jsx`
- `src/pages/CreateExperiment.jsx`
- `src/pages/ExperimentDetail.jsx`
- `src/pages/Analysis.jsx`
- `src/pages/VariantGenerator.jsx`
