#!/bin/bash
# Local AI Companion - GitHub 推送脚本
# 使用方法：./push_to_github.sh

set -e

echo "========================================"
echo "  Local AI Companion - GitHub 推送脚本"
echo "========================================"
echo ""

# 检查是否在git仓库中
if [ ! -d ".git" ]; then
    echo "[1/5] 初始化Git仓库..."
    git init
    git branch -M main
else
    echo "[1/5] Git仓库已存在"
fi

# 添加所有文件
echo "[2/5] 添加文件到暂存区..."
git add -A

# 提交
COMMIT_MESSAGE="Initial commit: Local AI Companion v1.0.0"
echo "[3/5] 提交更改: $COMMIT_MESSAGE"
git commit -m "$COMMIT_MESSAGE" || echo "  (没有新的更改)"

# 检查远程仓库
if git remote | grep -q "origin"; then
    echo "[4/5] 远程仓库已存在"
else
    echo "[4/5] 请输入你的GitHub仓库地址："
    echo "  格式: https://github.com/你的用户名/LocalAICompanion.git"
    read -p "  仓库地址: " REPO_URL

    if [ -n "$REPO_URL" ]; then
        git remote add origin "$REPO_URL"
        echo "  远程仓库已添加: $REPO_URL"
    else
        echo "  未输入仓库地址，跳过"
        echo ""
        echo "提示：你可以稍后手动添加远程仓库："
        echo "  git remote add origin https://github.com/你的用户名/LocalAICompanion.git"
        echo "  git push -u origin main"
        exit 0
    fi
fi

# 推送
echo "[5/5] 推送到GitHub..."
echo ""
echo "正在推送到 origin/main ..."
git push -u origin main || echo "推送失败，请检查仓库地址和权限"

echo ""
echo "========================================"
echo "  完成！"
echo "========================================"
