-- 初始化数据库
CREATE DATABASE IF NOT EXISTS `ai_agent_platform` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `ai_agent_platform`;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `phone` VARCHAR(20) NOT NULL COMMENT '手机号',
    `email` VARCHAR(100) COMMENT '邮箱',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 活动表
CREATE TABLE IF NOT EXISTS `activity` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '活动ID',
    `title` VARCHAR(200) NOT NULL COMMENT '活动标题',
    `description` TEXT COMMENT '活动描述',
    `location` VARCHAR(200) NOT NULL COMMENT '活动地点',
    `start_time` DATETIME NOT NULL COMMENT '开始时间',
    `end_time` DATETIME NOT NULL COMMENT '结束时间',
    `status` INT DEFAULT 0 COMMENT '状态：0-未开始，1-进行中，2-已结束',
    `price` DECIMAL(10, 2) DEFAULT 0.00 COMMENT '价格',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动表';

-- 订单表
CREATE TABLE IF NOT EXISTS `orders` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '订单ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `activity_id` BIGINT NOT NULL COMMENT '活动ID',
    `amount` DECIMAL(10, 2) NOT NULL COMMENT '订单金额',
    `status` INT DEFAULT 0 COMMENT '状态：0-待支付，1-已支付，2-已取消',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 政策表
CREATE TABLE IF NOT EXISTS `policy` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '政策ID',
    `title` VARCHAR(200) NOT NULL COMMENT '政策标题',
    `content` TEXT COMMENT '政策内容',
    `city` VARCHAR(50) COMMENT '适用城市',
    `publish_time` DATETIME COMMENT '发布时间',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='政策表';

-- 插入一些测试数据
INSERT INTO `user` (`username`, `phone`, `email`) VALUES 
('test_user', '13800138000', 'test@example.com');

-- location 须含「北京/上海/深圳」等，与 legacy-dummy listFromDatabase 中 location LIKE '%城市%' 一致
INSERT INTO `activity` (`title`, `description`, `location`, `start_time`, `end_time`, `status`, `price`) VALUES 
('2026海归创业分享会', '分享最新的海归创业经验和政策解读', '上海市浦东新区', '2026-03-15 14:00:00', '2026-03-15 17:00:00', 0, 0.00),
('AI技术落地研讨会', '探讨AI Agent在各行业的落地应用', '北京市海淀区', '2026-03-20 09:30:00', '2026-03-20 12:00:00', 0, 99.00),
('海归人才招聘会', '50+知名企业现场招聘，资深HR一对一简历指导', '北京国际会议中心', '2026-05-15 14:00:00', '2026-05-15 17:00:00', 0, 0.00),
('创业分享沙龙', '海归创业者分享创业经验，对接投资机构', '中关村创业大街（北京）', '2026-05-20 14:00:00', '2026-05-20 17:00:00', 0, 0.00),
('海归职业发展论坛', '行业大咖分享职业发展路径，Networking机会', '上海国际会议中心', '2026-05-25 09:00:00', '2026-05-25 12:00:00', 0, 0.00),
('深圳海归创业大赛', '展示创业项目，争夺创业扶持资金', '深圳湾创业广场', '2026-06-01 09:00:00', '2026-06-01 12:00:00', 0, 0.00);

INSERT INTO `policy` (`title`, `content`, `city`, `publish_time`) VALUES 
('上海市海归创业补贴政策', '符合条件的海归创业者可申请最高50万元的创业启动资金...', '上海', '2026-01-01 00:00:00'),
('北京市高层次人才引进计划', '针对海外高层次人才的落户和住房补贴政策...', '北京', '2026-02-01 00:00:00');
