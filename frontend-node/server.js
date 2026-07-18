const express = require("express");
const path = require("path");
const { exec } = require("child_process");

const app = express();
const port = Number(process.env.PORT || 5173);
const backendBase = process.env.BACKEND_BASE || "http://localhost:8082/api";
const defaultConflictId = process.env.CONFLICT_ID || "1";
const dataSourceUrl =
  process.env.DATA_SOURCE_URL || "https://ftp.ripe.net/rpki/apnic-afrinic.tal/2018/01/";
const swaggerUrl =
  process.env.SWAGGER_URL || "http://localhost:8082/api/swagger-ui.html";

app.use(express.json({ limit: "1mb" }));
app.use(express.static(path.join(__dirname, "public")));

function safeJsonParse(text) {
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch (_) {
    return { ok: false, value: null };
  }
}

async function callBackend(step, url, options = {}) {
  const resp = await fetch(url, options);
  const text = await resp.text();
  const parsed = safeJsonParse(text);
  return {
    step,
    ok: resp.ok,
    status: resp.status,
    raw: text,
    json: parsed.ok ? parsed.value : null
  };
}

function queryChaincode() {
  return new Promise((resolve) => {
    const cmd = `
export PATH=/home/xfn/fabric-samples/bin:$PATH
export FABRIC_CFG_PATH=/home/xfn/fabric-samples/config
export CORE_PEER_TLS_ENABLED=true
export CORE_PEER_LOCALMSPID=Org1MSP
export CORE_PEER_TLS_ROOTCERT_FILE=/home/xfn/fabric-samples/test-network/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt
export CORE_PEER_MSPCONFIGPATH=/home/xfn/fabric-samples/test-network/organizations/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp
export CORE_PEER_ADDRESS=localhost:7051
peer chaincode query -C mychannel -n rpkicc -c '{"Args":["GetAllConflicts"]}'
`;
    exec(cmd, { shell: "/bin/bash", timeout: 30000 }, (error, stdout, stderr) => {
      if (error) {
        resolve({
          step: "6/6 查询链上冲突记录",
          ok: false,
          error: stderr || error.message,
          raw: "",
          json: null
        });
        return;
      }
      const text = (stdout || "").trim();
      const parsed = safeJsonParse(text);
      let filtered = parsed.ok && Array.isArray(parsed.value) ? parsed.value.filter(
        (item) => item && (item.conflictType || item.txId || item.details)
      ) : null;
      resolve({
        step: "6/6 查询链上冲突记录",
        ok: true,
        raw: text,
        json: filtered
      });
    });
  });
}

app.post("/api/demo/run", async (req, res) => {
  const conflictId = String(req.body?.conflictId || defaultConflictId);
  const startedAt = new Date().toISOString();
  const steps = [];
  try {
    steps.push(await callBackend("1/6 健康检查", `${backendBase}/`));
    steps.push(await callBackend("2/6 下载并解析证书", `${backendBase}/cert/download-and-parse`, { method: "POST" }));
    steps.push(await callBackend("3/6 触发冲突检测", `${backendBase}/cert/detect-conflicts`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}"
    }));
    steps.push(await callBackend("4/6 查询冲突列表", `${backendBase}/cert/conflicts?page=1&size=10`));
    steps.push(await callBackend("5/6 推送冲突上链", `${backendBase}/cert/push-to-fabric?conflictId=${encodeURIComponent(conflictId)}`, { method: "POST" }));
    steps.push(await queryChaincode());
    res.json({
      success: true,
      startedAt,
      finishedAt: new Date().toISOString(),
      backendBase,
      conflictId,
      steps
    });
  } catch (error) {
    res.status(500).json({
      success: false,
      startedAt,
      finishedAt: new Date().toISOString(),
      error: error.message,
      steps
    });
  }
});

app.get("/api/health", (req, res) => {
  res.json({ ok: true, backendBase, dataSourceUrl, swaggerUrl });
});

app.get("/api/config", (req, res) => {
  res.json({ backendBase, dataSourceUrl, swaggerUrl });
});

app.get("/api/db/certs", async (req, res) => {
  const page = req.query.page || "1";
  const size = req.query.size || "10";
  const rir = req.query.rir;
  const hasConflict = req.query.hasConflict;
  const qs = new URLSearchParams({ page, size });
  if (rir) qs.set("rir", String(rir));
  if (hasConflict !== undefined) qs.set("hasConflict", String(hasConflict));
  const r = await callBackend(
    "DB 查询证书列表",
    `${backendBase}/cert/certs?${qs.toString()}`
  );
  res.status(r.ok ? 200 : 502).json(r);
});

app.get("/api/db/conflicts", async (req, res) => {
  const page = req.query.page || "1";
  const size = req.query.size || "10";
  const qs = new URLSearchParams({ page, size });
  const r = await callBackend(
    "DB 查询冲突列表",
    `${backendBase}/cert/conflicts?${qs.toString()}`
  );
  res.status(r.ok ? 200 : 502).json(r);
});

app.listen(port, () => {
  console.log(`UI ready: http://localhost:${port}`);
});
