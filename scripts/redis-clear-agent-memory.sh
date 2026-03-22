#!/usr/bin/env bash
# 删除 Redis 中 agent-core 使用的记忆 key（前缀 agent:），便于联调/测试。
#
# ⚠️ 在 macOS/Linux 的「终端」里执行本脚本，不要进入 redis-cli 后再粘贴 shell 管道命令。
#    若在 redis-cli 提示符下输入 redis-cli --scan ...，Redis 会报：
#    ERR unknown command 'redis-cli' ...
#
# 用法：
#   chmod +x scripts/redis-clear-agent-memory.sh
#   ./scripts/redis-clear-agent-memory.sh
#   REDIS_HOST=127.0.0.1 REDIS_PORT=6379 ./scripts/redis-clear-agent-memory.sh
#
# 若只想在 redis-cli 里查看（交互模式内只能用 Redis 原生命令）：
#   SCAN 0 MATCH agent:* COUNT 100
#   GET "agent:working:你的sessionId:你的userId"

set -euo pipefail

HOST="${REDIS_HOST:-localhost}"
PORT="${REDIS_PORT:-6379}"

count=0
while IFS= read -r key; do
  [[ -z "$key" ]] && continue
  redis-cli -h "$HOST" -p "$PORT" DEL "$key"
  count=$((count + 1))
done < <(redis-cli -h "$HOST" -p "$PORT" --scan --pattern 'agent:*')

echo "已删除 ${count} 个 key（pattern agent:*），Redis=${HOST}:${PORT}"
