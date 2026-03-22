#!/usr/bin/env bash
# 清空当前测试数据：legacy-dummy 的报名记录 + 活动列表恢复为初始样本
# 使用前请确保 legacy-dummy 已启动（默认 http://localhost:8083）

LEGACY_URL="${LEGACY_URL:-http://localhost:8083}"
echo "调用 $LEGACY_URL/api/activities/admin/reset ..."
curl -s -X POST "$LEGACY_URL/api/activities/admin/reset"
echo ""
echo "说明："
echo "  - legacy-dummy 活动/报名数据已按上文接口重置。"
echo "  - agent-core 在 profile=local 时工作记忆在进程内，需重启 agent-core 才清空。"
echo "  - agent-core 在 profile=middleware 等工作记忆在 Redis，请在终端执行（勿在 redis-cli 内）："
echo "      ./scripts/redis-clear-agent-memory.sh"
