#!/bin/bash
# 检查暂存的 .kt 文件代码区是否含中文（注释除外）
set -e
FILES=$(git diff --cached --name-only --diff-filter=ACM | grep '\.kt$' || true)
[ -z "$FILES" ] && exit 0
if grep -nP '[\x{4e00}-\x{9fff}]' $FILES | grep -vP '^\s*[^:]+:\d+:\s*(//|/\*|\*)' ; then
  echo "错误：代码中检测到硬编码中文，请使用 i18n 资源" >&2
  exit 1
fi
exit 0
