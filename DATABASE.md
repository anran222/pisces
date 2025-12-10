# 数据库配置说明

## 一、数据库准备

### 1.1 创建数据库

```sql
CREATE DATABASE IF NOT EXISTS pisces DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE pisces;
```

### 1.2 执行建表脚本

项目启动时会自动执行 `src/main/resources/db/schema.sql` 创建表结构。

也可以手动执行：

```bash
mysql -u root -p pisces < pisces-service/src/main/resources/db/schema.sql
```

## 二、数据库配置

编辑 `pisces-service/src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/pisces?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: root  # 修改为你的数据库密码
```

## 三、表结构

### pisces_user（用户表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 用户ID（主键，自增） |
| username | VARCHAR(50) | 用户名（唯一） |
| password | VARCHAR(255) | 密码（MD5加密） |
| email | VARCHAR(100) | 邮箱（唯一） |
| nickname | VARCHAR(50) | 昵称 |
| role | VARCHAR(20) | 角色：ADMIN/CREATOR/VIEWER |
| status | VARCHAR(20) | 状态：ACTIVE/INACTIVE/LOCKED |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

## 四、默认用户

系统会自动创建默认管理员用户：
- 用户名：`admin`
- 密码：`admin123`
- 角色：`ADMIN`
- 邮箱：`admin@pisces.com`

## 五、用户角色权限

### ADMIN（管理员）
- 所有权限
- 可以管理用户
- 可以创建、修改、删除所有实验

### CREATOR（创建者）
- 可以创建实验
- 可以修改、删除自己创建的实验
- 可以查看所有实验

### VIEWER（查看者）
- 只能查看实验
- 可以查看统计数据

## 六、权限说明

| 权限代码 | 说明 | ADMIN | CREATOR | VIEWER |
|---------|------|-------|---------|--------|
| EXPERIMENT_CREATE | 创建实验 | ✓ | ✓ | ✗ |
| EXPERIMENT_UPDATE | 更新实验 | ✓ | ✓* | ✗ |
| EXPERIMENT_DELETE | 删除实验 | ✓ | ✓* | ✗ |
| EXPERIMENT_VIEW | 查看实验 | ✓ | ✓ | ✓ |
| ANALYSIS_VIEW | 查看分析 | ✓ | ✓ | ✓ |
| USER_MANAGE | 用户管理 | ✓ | ✗ | ✗ |

*CREATOR只能修改/删除自己创建的实验

