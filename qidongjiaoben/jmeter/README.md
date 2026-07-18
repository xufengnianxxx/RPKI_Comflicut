# JMeter 压测说明

测试计划文件：`RPKI-Conflict-Detection-Test-Plan.jmx`

## 线程组设计

1. `TG-Functional-Endpoints`
   - 10 用户
   - 目标：`POST /api/test/functional`
2. `TG-Detect-Pair-10-50-100`
   - 默认 50 用户（可在 JMeter GUI 切换为 10/100）
   - 目标：`POST /api/cert/detect-pair-persist`

## 执行命令（非 GUI）

```bash
jmeter -n \
  -t qidongjiaoben/jmeter/RPKI-Conflict-Detection-Test-Plan.jmx \
  -l qidongjiaoben/jmeter/result.jtl \
  -e -o qidongjiaoben/jmeter/report
```

## 注意

- 后端 context-path 为 `/api`，所以路径必须是 `/api/...`
- `detect-pair-persist` 默认用 `certIdA=1, certIdB=2`，请确保数据库存在对应证书。
