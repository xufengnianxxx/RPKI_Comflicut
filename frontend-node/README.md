# RPKI Demo Web UI (Node.js)

独立前端项目（前后端分离），用于把 `demo-flow.sh` 的 6 步流程可视化展示到页面。

## 运行

1. 先确保后端已启动（默认 `http://localhost:8082/api`）
2. 安装并启动 UI：

```bash
cd frontend-node
npm install
npm start
```

3. 打开 `http://localhost:5173`

## 可选环境变量

- `PORT`：UI 端口（默认 `5173`）
- `BACKEND_BASE`：后端 API 地址（默认 `http://localhost:8082/api`）
- `CONFLICT_ID`：默认上链冲突 ID（默认 `1`）

## 说明

- 页面按钮执行顺序与 `demo-flow.sh` 保持一致：健康检查 → 下载解析 → 检测冲突 → 查列表 → 上链 → 查链上记录。
- `demo-flow.sh` 脚本本身未修改，终端演示效果保留。
