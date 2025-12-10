# pisces

a/b 实验系统

## 项目结构

本项目采用多模块Maven项目结构，分为三层架构：

```
pisces/
├── pom.xml                         # 父POM文件
├── pisces-common/                  # Common模块 - 存放DTO信息
│   ├── pom.xml
│   └── src/main/java/com/pisces/common/
│       ├── request/                # 请求DTO
│       │   ├── BaseRequest.java
│       │   ├── UserCreateRequest.java
│       │   └── UserQueryRequest.java
│       └── response/               # 响应DTO
│           ├── BaseResponse.java
│           └── UserResponse.java
├── pisces-service/                 # Service模块 - 用于逻辑处理
│   ├── pom.xml
│   └── src/main/java/com/pisces/
│       ├── PiscesApplication.java # Spring Boot 主应用类
│       └── service/
│           ├── UserService.java   # 用户服务接口
│           ├── impl/
│           │   └── UserServiceImpl.java # 用户服务实现
│           ├── exception/         # 异常处理
│           │   ├── GlobalExceptionHandler.java
│           │   └── BusinessException.java
│           └── aspect/            # 切面处理
│               ├── LoggingAspect.java
│               └── PerformanceAspect.java
└── pisces-api/                     # API模块 - 用于暴露接口
    ├── pom.xml
    └── src/main/java/com/pisces/api/
        └── user/
            └── UserController.java # 用户控制器
```

## 模块说明

### common模块 (artifactId: pisces-common, 包名: com.pisces.common)
- 存放所有请求和响应的DTO对象
- 包含数据验证注解
- 不依赖其他业务模块

### service模块 (artifactId: pisces-service, 包名: com.pisces.service)
- 实现业务逻辑处理
- 依赖common模块
- 包含Spring Boot主应用类
- 包含应用配置文件
- 包含全局异常处理（GlobalExceptionHandler）
- 包含切面处理（日志切面、性能监控切面等）
- 包含业务异常定义（BusinessException）

### api模块 (artifactId: pisces-api, 包名: com.pisces.api)
- 暴露REST API接口
- 依赖common和service模块
- 只负责接收请求，不处理业务逻辑和异常

## 技术栈

- Spring Boot 3.2.0
- Java 21
- Maven (多模块)
- Lombok
- Spring Validation
- Redis (Token存储)
- MyBatis (数据持久化)
- Zookeeper (配置管理)

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.6+
- Redis 6.0+ (用于Token存储)
- MySQL 8.0+ (用于数据存储)
- Zookeeper 3.9+ (用于配置管理，可选)

### 运行项目

```bash
# 编译整个项目
mvn clean compile

# 运行项目（在pisces-service模块中）
cd pisces-service
mvn spring-boot:run

# 或者在项目根目录运行
mvn spring-boot:run -pl pisces-service
```

项目启动后，访问地址：http://localhost:8080/api

## API接口示例

### 1. 用户登录（获取Token）
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
    "user": { ... },
    "expireIn": 86400
  }
}
```

### 2. 创建用户（需要Token）
```bash
POST /api/users
Authorization: Bearer {token}
Content-Type: application/json

{
  "username": "testuser",
  "password": "123456",
  "email": "test@example.com",
  "nickname": "测试用户"
}
```

### 3. 创建实验（需要Token）
```bash
POST /api/experiments
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "首页按钮颜色测试",
  ...
}
```

### 4. 查询用户列表（需要Token）
```bash
GET /api/users?username=test&pageNum=1&pageSize=10
Authorization: Bearer {token}
```

### 5. 用户登出（需要Token）
```bash
POST /api/auth/logout
Authorization: Bearer {token}
```

**注意**：除登录接口外，所有API都需要在请求头中携带Token。

## 项目说明

- **api层**: 只负责接收HTTP请求，调用service层处理业务逻辑，返回响应。不处理业务逻辑判断和异常处理
- **common层**: 存放请求和响应的DTO对象，用于数据传输
- **service层**: 实现业务逻辑处理、异常处理、切面处理（日志、性能监控等）

## 模块依赖关系

```
service (包含主应用类)
 ├── common
 └── (启动时加载api模块的Controller)

api
 ├── common
 └── service
      └── common
```
