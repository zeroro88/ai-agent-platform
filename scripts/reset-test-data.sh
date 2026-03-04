#!/usr/bin/env bash
# 清空当前测试数据：legacy-dummy 的报名记录 + 活动列表恢复为初始样本
# 使用前请确保 legacy-dummy 已启动（默认 http://localhost:8083）

LEGACY_URL="${LEGACY_URL:-http://localhost:8083}"
echo "调用 $LEGACY_URL/api/activities/admin/reset ..."
curl -s -X POST "$LEGACY_URL/api/activities/admin/reset"
echo ""
echo "说明：agent-core 的会话/工作记忆为内存存储，清空需重启 agent-core。"
