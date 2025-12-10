-- 用户表
CREATE TABLE IF NOT EXISTS `pisces_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(255) NOT NULL COMMENT '密码（加密后）',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `nickname` VARCHAR(50) DEFAULT NULL COMMENT '昵称',
    `role` VARCHAR(20) NOT NULL DEFAULT 'VIEWER' COMMENT '角色：ADMIN-管理员, CREATOR-创建者, VIEWER-查看者',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-激活, INACTIVE-未激活, LOCKED-锁定',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`),
    KEY `idx_role` (`role`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 插入默认管理员用户（密码：admin123，MD5加密后）
-- MD5('admin123') = 0192023a7bbd73250516f069df18b500
INSERT INTO `pisces_user` (`username`, `password`, `email`, `nickname`, `role`, `status`) 
VALUES ('admin', '0192023a7bbd73250516f069df18b500', 'admin@pisces.com', '管理员', 'ADMIN', 'ACTIVE')
ON DUPLICATE KEY UPDATE `username`=`username`;

