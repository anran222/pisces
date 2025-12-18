# Pisces Java SDK

## Maven依赖

```xml
<dependency>
    <groupId>com.pisces</groupId>
    <artifactId>pisces-sdk-java</artifactId>
    <version>1.0.0</version>
</dependency>
```

## 快速开始

```java
import com.pisces.sdk.PiscesClient;
import java.util.HashMap;
import java.util.Map;

// 1. 初始化客户端
PiscesClient client = new PiscesClient("http://localhost:8080/api");
String experimentId = "exp_price_001";

// 2. 获取访客ID（从Cookie、Session等获取）
String visitorId = getVisitorId(request);

// 3. 分配访客到实验组
String groupId = client.assignGroup(experimentId, visitorId);
System.out.println("访客所在组: " + groupId);

// 4. 获取实验配置
ExperimentConfig experiment = client.getExperiment(experimentId);
ExperimentConfig.GroupConfig groupConfig = experiment.getGroups().get(groupId);
System.out.println("组配置: " + groupConfig.getConfig());

// 5. 上报浏览事件
Map<String, Object> productData = new HashMap<>();
productData.put("productId", "iphone_001");
productData.put("productPrice", 4500.0);
productData.put("marketPrice", 6000.0);
productData.put("productModel", "iPhone 13 Pro");
productData.put("condition", "95新");
client.reportView(experimentId, visitorId, productData);

// 6. 交易完成时上报（关键指标）
Map<String, Object> transactionData = new HashMap<>();
transactionData.put("transactionId", "txn_001");
transactionData.put("productId", "iphone_001");
transactionData.put("transactionPrice", 4800.0);  // 实际成交价格（核心指标）
transactionData.put("listPrice", 4500.0);
transactionData.put("marketPrice", 6000.0);
client.reportTransaction(experimentId, visitorId, transactionData);
```

## Spring Boot集成

```java
@Service
public class ExperimentService {
    
    private final PiscesClient piscesClient;
    private final String experimentId = "exp_price_001";
    
    public ExperimentService() {
        this.piscesClient = new PiscesClient("http://localhost:8080/api");
    }
    
    /**
     * 获取访客实验组
     */
    public String getGroup(String visitorId) {
        return piscesClient.assignGroup(experimentId, visitorId);
    }
    
    /**
     * 上报浏览事件
     */
    public void reportView(String visitorId, Map<String, Object> productData) {
        piscesClient.reportView(experimentId, visitorId, productData);
    }
    
    /**
     * 上报成交事件
     */
    public void reportTransaction(String visitorId, Map<String, Object> transactionData) {
        piscesClient.reportTransaction(experimentId, visitorId, transactionData);
    }
}
```

## API文档

### PiscesClient

#### 构造函数

```java
PiscesClient(String apiBaseUrl)
```

#### 方法

##### assignGroup(String experimentId, String visitorId)

分配访客到实验组。

**返回**：`String` - 实验组ID

##### getExperiment(String experimentId)

获取实验配置。

**返回**：`ExperimentConfig` - 实验配置对象

##### reportEvent(String experimentId, String visitorId, String eventType, String eventName, Map<String, Object> properties)

上报事件。

##### reportView(String experimentId, String visitorId, Map<String, Object> productData)

上报浏览事件。

##### reportClick(String experimentId, String visitorId, Map<String, Object> clickData)

上报咨询事件。

##### reportTransaction(String experimentId, String visitorId, Map<String, Object> transactionData)

上报成交事件（关键指标）。

## 访客ID管理

```java
// 从Cookie获取
public String getVisitorIdFromCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies != null) {
        for (Cookie cookie : cookies) {
            if ("pisces_visitor_id".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
    }
    return null;
}

// 生成新的访客ID
public String generateVisitorId() {
    return "visitor_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
}
```

---

**更多信息请查看 [完整实施指南](COMPLETE_GUIDE.md)**
