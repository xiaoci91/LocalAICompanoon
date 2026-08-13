@echo off
REM Local AI Companion - GitHub 推送脚本 (Windows版)
REM 使用方法：双击运行 或 在命令行执行 push_to_github.bat

echo ========================================
echo   Local AI Companion - GitHub 推送脚本
echo ========================================
echo.

REM 检查是否在git仓库中
if not exist ".git" (
    echo [1/5] 初始化Git仓库...
    git init
    git branch -M main
) else (
    echo [1/5] Git仓库已存在
)

REM 添加所有文件
echo [2/5] 添加文件到暂存区...
git add -A

REM 提交
echo [3/5] 提交更改...
git commit -m "Initial commit: Local AI Companion v1.0.0" || echo   (没有新的更改)

REM 检查远程仓库
git remote | findstr "origin" >nul
if %errorlevel%==0 (
    echo [4/5] 远程仓库已存在
) else (
    echo [4/5] 请输入你的GitHub仓库地址：
    echo   格式: https://github.com/你的用户名/LocalAICompanion.git
    set /p REPO_URL=  仓库地址:

    if defined REPO_URL (
        git remote add origin %REPO_URL%
        echo   远程仓库已添加
    ) else (
        echo   未输入仓库地址，跳过
        echo.
        echo 提示：你可以稍后手动添加远程仓库：
        echo   git remote add origin https://github.com/你的用户名/LocalAICompanion.git
        echo   git push -u origin main
        pause
        exit /b
    )
)

REM 推送
echo [5/5] 推送到GitHub...
echo.
git push -u origin main || echo 推送失败，请检查仓库地址和权限

echo.
echo ========================================
echo   完成！
echo ========================================
pause
