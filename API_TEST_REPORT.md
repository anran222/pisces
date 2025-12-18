# API测试报告 - 实验ID: exp_5d40083a

## 测试时间
2025-12-18

## 测试结果汇总

### ✅ 正常工作的接口

#### 1. 基础统计接口
- **URL**: `GET /api/analysis/experiment/exp_5d40083a/statistics`
- **状态**: ✅ 正常
- **响应**: 返回了4个实验组（A、B、C、D）的完整统计数据
- **数据摘要**:
  - A组：208访客，转化率8.17%
  - B组：194访客，转化率9.79%
  - C组：192访客，转化率11.98%
  - D组：206访客，转化率8.74%

#### 2. 贝叶斯分析接口
- **URL**: `GET /api/analysis/experiment/exp_5d40083a/bayesian`
- **状态**: ✅ 正常
- **响应**: 返回了各变体相对于基准组A的胜率
- **数据摘要**:
  - B组胜率：71.39%
  - C组胜率：89.38%（最优）
  - D组胜率：57.61%
  - 所有组都未达到95%阈值，建议继续实验

#### 3. 实验详情接口
- **URL**: `GET /api/experiments/exp_5d40083a`
- **状态**: ✅ 正常
- **响应**: 返回了完整的实验配置信息

#### 4. MAB算法参数接口
- **URL**: `GET /api/traffic/experiment/exp_5d40083a/mab/beta?groupId=A`
- **状态**: ✅ 正常
- **响应**: 返回了Thompson Sampling的Beta分布参数（alpha=10, beta=9）

#### 5. 因果推断接口（DID方法）
- **URL**: `POST /api/analysis/experiment/exp_5d40083a/causal-inference?method=DID&treatmentGroupId=C&controlGroupId=A`
- **状态**: ✅ 正常
- **请求体**: 
  ```json
  {
    "beforePeriodStart": "2025-12-01T00:00:00",
    "beforePeriodEnd": "2025-12-10T00:00:00",
    "afterPeriodStart": "2025-12-11T00:00:00",
    "afterPeriodEnd": "2025-12-18T00:00:00"
  }
  ```
- **响应**: 返回了DID分析结果（处理效应为0.0，不显著）

#### 6. HTE分析接口
- **URL**: `POST /api/analysis/experiment/exp_5d40083a/hte?treatmentGroupId=C&controlGroupId=A`
- **状态**: ✅ 正常
- **请求体**: `["age","gender","device"]`
- **响应**: 返回了不同用户群体的异质性处理效应分析结果

---

### ❌ 有问题的接口

#### 1. 组对比接口
- **URL**: `GET /api/analysis/experiment/exp_5d40083a/compare`
- **状态**: ❌ 错误（500）
- **错误信息**: "系统异常: null"
- **问题分析**: 
  - 可能是 `compareWithBaseline` 方法中某个对象为null
  - 已添加空值检查，但问题仍然存在
  - 需要进一步调试

#### 2. 因果推断接口（错误路径）
- **错误URL**: `GET /api/analysis/experiment/exp_5d40083a/causal`
- **状态**: ❌ 404错误
- **问题**: 
  - 实际接口路径是 `/causal-inference`，不是 `/causal`
  - 需要使用 `POST` 方法，不是 `GET`
  - 需要提供查询参数和请求体

#### 3. HTE分析接口（错误方法）
- **错误URL**: `GET /api/analysis/experiment/exp_5d40083a/hte`
- **状态**: ❌ 405错误（方法不允许）
- **问题**: 
  - 需要使用 `POST` 方法，不是 `GET`
  - 需要提供查询参数和请求体

---

## 接口使用说明

### 正确的接口调用方式

#### 1. 因果推断接口
```bash
curl -X POST 'http://localhost:9990/api/analysis/experiment/{experimentId}/causal-inference?method=DID&treatmentGroupId=C&controlGroupId=A' \
  -H "Content-Type: application/json" \
  -d '{
    "beforePeriodStart": "2025-12-01T00:00:00",
    "beforePeriodEnd": "2025-12-10T00:00:00",
    "afterPeriodStart": "2025-12-11T00:00:00",
    "afterPeriodEnd": "2025-12-18T00:00:00"
  }'
```

**支持的方法**:
- `DID`: 双重差分法
- `PSM`: 倾向得分匹配
- `CAUSAL_FOREST`: 因果森林

#### 2. HTE分析接口
```bash
curl -X POST 'http://localhost:9990/api/analysis/experiment/{experimentId}/hte?treatmentGroupId=C&controlGroupId=A' \
  -H "Content-Type: application/json" \
  -d '["age","gender","device"]'
```

#### 3. 组对比接口（待修复）
```bash
curl http://localhost:9990/api/analysis/experiment/{experimentId}/compare
```

---

## 发现的问题

### 1. 组对比接口空指针异常
- **位置**: `AnalysisServiceImpl.compareGroups()` 或 `compareWithBaseline()`
- **可能原因**: 
  - `getEventCounts()` 返回null
  - `getConversionRate()` 返回null
  - 其他对象为null
- **修复状态**: 已添加部分空值检查，但问题仍然存在，需要进一步调试

### 2. 接口路径不一致
- **问题**: 文档中可能使用了简化的路径（如 `/causal`），但实际接口路径不同（如 `/causal-inference`）
- **建议**: 统一接口路径命名，或更新文档

### 3. HTTP方法不一致
- **问题**: 某些接口需要使用POST方法，但文档中可能只提到了GET方法
- **建议**: 在文档中明确标注每个接口的HTTP方法

---

## 建议

1. **修复组对比接口**: 需要进一步调试，找出空指针异常的具体位置
2. **更新文档**: 确保所有接口路径和HTTP方法都正确
3. **添加单元测试**: 为 `compareGroups` 方法添加单元测试，覆盖各种边界情况
4. **改进错误处理**: 在可能为null的地方添加更详细的错误信息

---

## 测试命令汇总

```bash
# 1. 基础统计
curl http://localhost:9990/api/analysis/experiment/exp_5d40083a/statistics

# 2. 贝叶斯分析
curl http://localhost:9990/api/analysis/experiment/exp_5d40083a/bayesian

# 3. 实验详情
curl http://localhost:9990/api/experiments/exp_5d40083a

# 4. MAB参数
curl "http://localhost:9990/api/traffic/experiment/exp_5d40083a/mab/beta?groupId=A"

# 5. 因果推断（DID）
curl -X POST 'http://localhost:9990/api/analysis/experiment/exp_5d40083a/causal-inference?method=DID&treatmentGroupId=C&controlGroupId=A' \
  -H "Content-Type: application/json" \
  -d '{"beforePeriodStart":"2025-12-01T00:00:00","beforePeriodEnd":"2025-12-10T00:00:00","afterPeriodStart":"2025-12-11T00:00:00","afterPeriodEnd":"2025-12-18T00:00:00"}'

# 6. HTE分析
curl -X POST 'http://localhost:9990/api/analysis/experiment/exp_5d40083a/hte?treatmentGroupId=C&controlGroupId=A' \
  -H "Content-Type: application/json" \
  -d '["age","gender","device"]'

# 7. 组对比（待修复）
curl http://localhost:9990/api/analysis/experiment/exp_5d40083a/compare
```
