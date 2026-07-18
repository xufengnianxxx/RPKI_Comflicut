# Caliper 压测说明

本目录提供论文第 4 章的性能测试脚本骨架，面向后端 REST 接口压测（非链码交易吞吐基准）。

## 前置条件

1. 后端已启动：`http://127.0.0.1:8082/api`
2. 已完成测试数据生成与入库（至少存在 2 条证书）
3. Node.js 18+

## 安装与执行

```bash
cd caliper
npm install
cd ..
npm run caliper:test
```

## 工作负载

- `query-functional-report`: `GET /api/test/functional/report`
- `run-functional-small`: `POST /api/test/functional`
- `get-certs-page`: `GET /api/cert/certs?page=&size=`
- `detect-pair`: `POST /api/cert/detect-pair-persist`

## 结果建议

Caliper 会在控制台输出 TPS、延迟分位数等指标。建议将结果汇总到
`qidongjiaoben/测试结果-性能测试.md` 的“Caliper”小节。
