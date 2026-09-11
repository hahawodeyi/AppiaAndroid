#!/bin/bash
# 检查暂存的 .kt 文件代码区是否含中文（注释除外）
# 用 perl 而非 grep -P：macOS BSD grep 不支持 -P；且需剔除行尾 // 注释
set -e
FILES=$(git diff --cached --name-only --diff-filter=ACM | grep '\.kt$' || true)
[ -z "$FILES" ] && exit 0
violations=$(perl -CSD -ne '
  s{(?<!:)//.*$}{};   # 剔除行尾 // 注释（(?<!:) 避免误伤 https:// 字符串）
  s{/\*.*?\*/}{}g;    # 剔除同行 /* */ 注释
  s{^\s*/\*+.*$}{};   # 剔除跨行块注释起始行（如 KDoc 的 /**）
  s{^\s*\*.*$}{};     # 剔除块注释中间与收尾行（* ... */
  print "$ARGV:$.: $_" if /\p{Han}/;
  close ARGV if eof;  # 每个文件重置行号
' $FILES)
if [ -n "$violations" ]; then
  echo "$violations"
  echo "错误：代码中检测到硬编码中文，请使用 i18n 资源" >&2
  exit 1
fi
exit 0
