-- 已有 MySQL 卷、仅含旧版 2 条 activity 时手动补数据（勿与 init 重复执行于全新库）
-- 用法示例（在仓库根目录，务必指定客户端字符集，避免中文乱码）：
--   docker exec -i agent-mysql mysql -uroot -proot --default-character-set=utf8mb4 ai_agent_platform < docker/mysql/seed_activity_extra.sql
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `ai_agent_platform`;

INSERT INTO `activity` (`title`, `description`, `location`, `start_time`, `end_time`, `status`, `price`) VALUES 
('海归人才招聘会', '50+知名企业现场招聘，资深HR一对一简历指导', '北京国际会议中心', '2026-05-15 14:00:00', '2026-05-15 17:00:00', 0, 0.00),
('创业分享沙龙', '海归创业者分享创业经验，对接投资机构', '中关村创业大街（北京）', '2026-05-20 14:00:00', '2026-05-20 17:00:00', 0, 0.00),
('海归职业发展论坛', '行业大咖分享职业发展路径，Networking机会', '上海国际会议中心', '2026-05-25 09:00:00', '2026-05-25 12:00:00', 0, 0.00),
('深圳海归创业大赛', '展示创业项目，争夺创业扶持资金', '深圳湾创业广场', '2026-06-01 09:00:00', '2026-06-01 12:00:00', 0, 0.00);
