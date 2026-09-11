#!/bin/bash
# 启用项目 git 钩子（pre-commit 中文检查）：新克隆仓库后执行一次即可。
#   ./scripts/setup-hooks.sh
set -e
git config core.hooksPath .githooks
echo "已设置 core.hooksPath=.githooks，pre-commit 中文检查生效"
