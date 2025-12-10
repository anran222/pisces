# Token认证使用说明

## 一、Token机制

系统使用Token进行用户认证，所有API请求（除登录接口外）都需要携带Token。

## 二、Token生成和验证流程

1. **用户登录** → 生成Token
2. **API请求** → TokenAspect切面自动校验Token
3. **Token有效** → 继续处理请求
4. **Token无效** → 返回401错误

## 三、API使用示例

### 3.1 用户登录

```bash
POST /api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "admin123"
}
```

响应：
```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "0192023a7bbd73250516f069df18b500",
    "user": {
      "id": 1,
      "username": "admin",
      "email": "admin@pisces.com",
      "nickname": "管理员",
      "role": "ADMIN",
      "status": "ACTIVE"
    },
    "expireIn": 86400
  }
}
```

### 3.2 使用Token访问API

#### 方式1：使用Authorization头（推荐）
```bash
GET /api/experiments
Authorization: Bearer {token}
```

#### 方式2：使用X-Token头
```bash
GET /api/experiments
X-Token: {token}
```

### 3.3 创建实验（需要Token）

```bash
POST /api/experiments
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "首页按钮颜色测试",
  ...
}
```

### 3.4 刷新Token

```bash
POST /api/auth/refresh
Authorization: Bearer {token}
```

响应：
```json
{
  "code": 200,
  "message": "Token刷新成功",
  "data": {
    "token": "新的token值",
    "user": {
      "id": 1,
      "username": "admin",
      ...
    },
    "expireIn": 86400
  }
}
```

### 3.5 用户登出

```bash
POST /api/auth/logout
Authorization: Bearer {token}
```

## 四、Token配置

在 `application.yml` 中配置：

```yaml
token:
  expire-hours: 24  # Token过期时间（小时）
  refresh-threshold-hours: 2  # Token刷新阈值（小时），在过期前多少小时可以刷新
  cleanup-interval-minutes: 60  # Token清理间隔（分钟），定期清理过期Token和黑名单
```

## 五、Token校验规则

1. **Token不存在** → 返回401 "未登录或Token已过期，请先登录"
2. **Token在黑名单中** → 返回401 "Token已失效"（登出后的Token会被加入黑名单）
3. **Token已过期** → 返回401 "Token已过期"
4. **用户状态异常** → 返回401 "用户状态异常，Token已失效"
5. **Token有效** → 自动续期（如果即将过期），继续处理请求

## 六、Token切面说明

`TokenAspect` 会自动拦截所有API请求，进行Token校验：

- ✅ **自动校验**：所有Controller方法（除`@NoTokenRequired`标记的）
- ✅ **CORS支持**：自动跳过OPTIONS预检请求
- ✅ **自动续期**：Token验证通过后，如果即将过期（在刷新阈值内），自动延长过期时间
- ✅ **用户信息注入**：将用户名、用户ID、Token等信息设置到请求属性中
- ✅ **多方式支持**：支持从`Authorization`、`X-Token`头或`token`参数获取Token

## 七、不需要Token的接口

使用 `@NoTokenRequired` 注解标记不需要Token校验的接口，可以标注在类或方法上：

```java
// 方法级别
@NoTokenRequired
@PostMapping("/public")
public BaseResponse<?> publicApi() {
    // 不需要Token
}

// 类级别（整个Controller都不需要Token）
@NoTokenRequired
@RestController
@RequestMapping("/public")
public class PublicController {
    // 所有方法都不需要Token
}
```

## 八、错误响应示例

### Token不存在
```json
{
  "code": 401,
  "message": "未登录或Token已过期，请先登录",
  "data": null,
  "timestamp": 1234567890
}
```

### Token已过期
```json
{
  "code": 401,
  "message": "Token已过期",
  "data": null,
  "timestamp": 1234567890
}
```

## 九、Token存储和管理

### 9.1 存储方式

系统使用Redis存储Token，包含：
- **Token存储**：存储有效的Token信息（key: `pisces:token:{token}`）
- **黑名单**：存储已登出的Token（key: `pisces:token:blacklist:{token}`，保留7天）

### 9.2 Redis自动过期机制

Redis会自动处理Token过期：
- Token存储时会设置过期时间（默认24小时）
- Redis会在Token过期后自动删除，无需手动清理
- 黑名单记录也会自动过期（默认7天）

### 9.3 Token刷新机制

- Token在过期前（刷新阈值内）会自动续期
- 可以通过 `/api/auth/refresh` 接口主动刷新Token
- 刷新后会生成新Token，旧Token会被加入黑名单

### 9.4 Redis配置

在 `application.yml` 中配置Redis连接：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password:  # Redis密码，如果没有密码则留空
      database: 0
      timeout: 3000
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0
          max-wait: -1

token:
  redis:
    key-prefix: "pisces:token:"  # Token存储的key前缀
    blacklist-prefix: "pisces:token:blacklist:"  # Token黑名单的key前缀
    blacklist-expire-days: 7  # 黑名单过期时间（天）
```

### 9.5 生产环境建议

- ✅ 已使用Redis存储Token（支持分布式）
- ✅ 支持多设备登录管理
- ✅ 考虑使用JWT替代当前实现
- ✅ 添加Token访问频率限制
- ✅ 配置Redis集群以提高可用性

## 十、获取当前用户信息

在Controller或Service中，可以使用 `TokenContext` 工具类方便地获取当前登录用户信息：

```java
import com.pisces.service.token.TokenContext;

@RestController
@RequestMapping("/api/example")
public class ExampleController {
    
    @GetMapping("/current-user")
    public BaseResponse<?> getCurrentUser() {
        // 获取当前用户名（如果未登录会抛异常）
        String username = TokenContext.getCurrentUsername();
        
        // 获取当前用户ID（如果未登录会抛异常）
        Long userId = TokenContext.getCurrentUserId();
        
        // 获取当前Token
        String token = TokenContext.getCurrentToken();
        
        // 获取当前TokenInfo
        TokenInfo tokenInfo = TokenContext.getCurrentTokenInfo();
        
        // 安全获取（如果未登录返回null，不抛异常）
        String usernameOrNull = TokenContext.getCurrentUsernameOrNull();
        Long userIdOrNull = TokenContext.getCurrentUserIdOrNull();
        
        // 检查是否已登录
        boolean isLoggedIn = TokenContext.isLoggedIn();
        
        return BaseResponse.success("获取成功", ...);
    }
}
```

## 十一、Token黑名单机制

系统实现了Token黑名单机制，确保登出后的Token立即失效：

1. **登出时**：Token会被加入黑名单
2. **验证时**：如果Token在黑名单中，验证会失败
3. **自动清理**：黑名单中超过7天的记录会被自动清理

这样可以有效防止Token被盗用后的安全问题。

