#!/bin/bash

# RPKI 冲突检测与 Fabric 存证系统 - 运行脚本
# 用途: 帮助快速编译和运行项目

set -e

echo "╔═══════════════════════════════════════════════════════════════════╗"
echo "║      RPKI 冲突检测与 Fabric 存证系统 - 项目启动脚本               ║"
echo "╚═══════════════════════════════════════════════════════════════════╝"
echo ""

# 项目根目录
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "项目目录: $PROJECT_DIR"
echo ""

# 启动前先清理旧进程，避免端口占用
stop_existing_processes() {
    echo "检查并清理旧进程..."
    local killed=0

    # 1) 清理 maven spring-boot:run 启动进程
    if pgrep -af "mvn.*spring-boot:run" > /dev/null; then
        pkill -f "mvn.*spring-boot:run" || true
        killed=1
    fi

    # 2) 清理已运行的项目 java -jar 进程
    if pgrep -af "conflict-checker-backend-1.0.0.jar" > /dev/null; then
        pkill -f "conflict-checker-backend-1.0.0.jar" || true
        killed=1
    fi

    # 3) 清理主类方式启动的进程
    if pgrep -af "com.rpki.conflictchecker.RpkiConflictCheckerApplication" > /dev/null; then
        pkill -f "com.rpki.conflictchecker.RpkiConflictCheckerApplication" || true
        killed=1
    fi

    if [ "$killed" -eq 1 ]; then
        sleep 1
        echo "✓ 旧进程已清理"
    else
        echo "✓ 未发现旧进程"
    fi
    echo ""
}

# 检查 Maven 是否安装
if ! command -v mvn &> /dev/null; then
    echo "❌ Maven 未安装，请先安装 Maven 3.8+"
    exit 1
fi

echo "✓ Maven 已安装"
echo ""

# 检查 Java 版本
JAVA_VERSION=$(java -version 2>&1 | grep -oP 'version "\K[^"]*' | head -1)
echo "✓ Java 版本: $JAVA_VERSION"
echo ""

# 编译选项
read -p "请选择操作 (1=编译, 2=运行, 3=打包, 4=清理, 5=全部): " option

case $option in
    1)
        echo "开始编译..."
        cd "$PROJECT_DIR"
        mvn clean compile
        echo "✓ 编译完成"
        ;;
    2)
        echo "开始运行..."
        cd "$PROJECT_DIR"
        stop_existing_processes
        mvn spring-boot:run
        ;;
    3)
        echo "开始打包..."
        cd "$PROJECT_DIR"
        mvn clean package -DskipTests
        echo "✓ 打包完成"
        echo "JAR 文件: $PROJECT_DIR/target/conflict-checker-backend-1.0.0.jar"
        ;;
    4)
        echo "清理构建产物..."
        cd "$PROJECT_DIR"
        mvn clean
        echo "✓ 清理完成"
        ;;
    5)
        echo "开始编译 + 打包 + 运行..."
        cd "$PROJECT_DIR"
        stop_existing_processes
        mvn clean package -DskipTests
        echo ""
        echo "╔═══════════════════════════════════════════════════════════════════╗"
        echo "║                     开始运行项目                                   ║"
        echo "╚═══════════════════════════════════════════════════════════════════╝"
        echo ""
        java -jar "$PROJECT_DIR/target/conflict-checker-backend-1.0.0.jar"
        ;;
    *)
        echo "❌ 无效的选项，请输入 1-5"
        exit 1
        ;;
esac

echo ""
echo "完成！"
