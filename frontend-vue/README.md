# RPKI 证书冲突校验 — Vue3 前端

## 如何运行

```bash
cd frontend-vue
npm install
npm run dev
```

浏览器访问 **http://127.0.0.1:5173**。

生产构建：

```bash
npm run build
npm run preview   # 本地预览 dist
```

## 与后端对接注意事项

1. **上下文路径**：Spring Boot 已配置 `server.servlet.context-path: /api`，前端请求统一为相对路径 `/api/cert/...`、`/api/fabric/chain/...`。
2. **开发代理**：`vite.config.ts` 将 `/api` 代理到 `http://127.0.0.1:8082`。若后端端口不同，请修改 `vite.config.ts` 中 `server.proxy['/api'].target`。
3. **列表搜索**：需后端支持 `GET /api/cert/certs?keyword=`（按 `cert_hash` 与 `id` 字符串模糊匹配）。若使用旧版后端，可去掉前端的 `keyword` 参数或升级后端。
4. **双证链下检测**：使用 `POST /api/cert/detect-pair`，**不要**用 `POST /api/cert/detect-conflicts` 做双证演示（后者为全库扫描）。
5. **链上检测**：`POST /api/fabric/chain/detect-on-chain` 需要两证 `cert_hash`；真实 Fabric 环境下通常需先将证书写入账本（`storeCertificateBatch`）。MOCK 模式下行为以后端为准。
6. **CORS**：开发阶段走 Vite 代理即可无跨域问题；生产环境若前后端分离，需在 Spring 配置 CORS 或将前端静态资源与 API 同域部署。
