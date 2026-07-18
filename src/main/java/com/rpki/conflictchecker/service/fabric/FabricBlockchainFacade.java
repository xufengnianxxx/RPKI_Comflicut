package com.rpki.conflictchecker.service.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rpki.conflictchecker.dto.fabric.CertificateRedactedPayload;
import com.rpki.conflictchecker.dto.fabric.ConflictEvidencePayload;
import com.rpki.conflictchecker.dto.fabric.FabricChaincodeTxResponse;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.fabric.gateway.Contract;
import org.hyperledger.fabric.gateway.Gateway;
import org.hyperledger.fabric.gateway.Network;
import org.hyperledger.fabric.gateway.Transaction;
import org.hyperledger.fabric.gateway.Wallet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 与 Java 链码 {@code RpkiccjavaContract}（包 {@code rpkiccjava}）对接（Fabric Gateway Java 1.4.x）。
 * <p>写操作使用 {@link Contract#createTransaction(String)} 再 {@link Transaction#submit(String...)}，
 * 与直接 {@link Contract#submitTransaction(String, String...)} 等价，便于日后换为 Gateway 2.x 时注入拦截器。</p>
 */
@Slf4j
@Service
public class FabricBlockchainFacade {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @Value("${fabric.mode:MOCK}")
    private String mode;

    @Value("${fabric.gateway.connection-profile:}")
    private String connectionProfile;

    @Value("${fabric.channel:mychannel}")
    private String channelName;

    @Value("${fabric.chaincode:rpkiccjava}")
    private String chaincodeId;

    @Value("${fabric.identity.label:admin}")
    private String identityLabel;

    @Value("${fabric.wallet.path:wallet}")
    private String walletPath;

    @Value("${fabric.msp-id:Org1MSP}")
    private String mspId;

    @Value("${fabric.identity.cert-path:}")
    private String certPath;

    @Value("${fabric.identity.key-path:}")
    private String keyPath;

    public boolean isReal() {
        return "REAL".equalsIgnoreCase(mode);
    }

    /**
     * 批量上链脱敏证书（链码 {@code storeCertificateBatch}）。
     */
    public FabricSubmitResult submitCertificateBatchToFabric(List<CertificateRedactedPayload> batch) {
        if (!isReal()) {
            return FabricSubmitResult.mock("MOCK-cert-" + UUID.randomUUID(),
                    batch == null ? 0 : batch.size());
        }
        String json = GSON.toJson(batch == null ? List.of() : batch);
        return withContract(c -> submitParsed(c, "storeCertificateBatch", json));
    }

    /**
     * 单条冲突存证（链码 {@code recordConflictEvidence}）。
     */
    public FabricSubmitResult submitConflictToFabric(ConflictEvidencePayload payload) {
        if (!isReal()) {
            return FabricSubmitResult.mock("MOCK-conflict-" + UUID.randomUUID(), 1);
        }
        String json = GSON.toJson(payload);
        return withContract(c -> submitParsed(c, "recordConflictEvidence", json));
    }

    /**
     * 批量冲突存证（链码 {@code recordConflictEvidenceBatch}）。
     */
    public FabricSubmitResult submitConflictBatchToFabric(List<ConflictEvidencePayload> batch) {
        if (!isReal()) {
            return FabricSubmitResult.mock("MOCK-conflict-batch-" + UUID.randomUUID(),
                    batch == null ? 0 : batch.size());
        }
        String json = GSON.toJson(batch == null ? List.of() : batch);
        return withContract(c -> submitParsed(c, "recordConflictEvidenceBatch", json));
    }

    /** @deprecated 请使用 {@link #submitCertificateBatchToFabric(List)} */
    @Deprecated
    public String storeCertificateBatch(List<CertificateRedactedPayload> batch) {
        FabricSubmitResult r = submitCertificateBatchToFabric(batch);
        return r.rawResponse();
    }

    /**
     * 链上确定性检测（两枚已存证证书）。返回 JSON，结构见 {@link com.rpki.conflictchecker.dto.fabric.ChaincodeConflictDetectResult}。
     */
    public String detectConflictOnChain(String certHashA, String certHashB) {
        if (!isReal()) {
            return mockDetectJson("MOCK-detect-" + UUID.randomUUID(), "NO_CONFLICT", "NONE",
                    List.of(certHashA, certHashB), 1, 0);
        }
        return withContractString(c -> bytesToString(submitRaw(c, "detectConflictOnChain", certHashA, certHashB)));
    }

    /**
     * 批量链上检测。请求体 JSON 示例：
     * <pre>
     * {"mode":"STATE_HASHES","certHashes":["ab..","cd.."]}
     * {"mode":"INLINE_ASSETS","certificates":[{...CertificateRedactedPayload...},...]}
     * </pre>
     */
    public String detectConflictOnChainBatch(String requestJson) {
        if (!isReal()) {
            return mockDetectJson("MOCK-detect-batch-" + UUID.randomUUID(), "NO_CONFLICT", "NONE", List.of(), 0, 0);
        }
        return withContractString(c -> bytesToString(submitRaw(c, "detectConflictOnChainBatch", requestJson)));
    }

    private static String mockDetectJson(String txId, String verdict, String primary,
                                         List<String> involved, int pairs, int conflicting) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("txId", txId);
        m.put("verdict", verdict);
        m.put("primaryConflictType", primary);
        m.put("legacyWorstCode", "NONE");
        m.put("findings", List.of());
        m.put("rawFindings", List.of());
        m.put("log", List.of("MOCK"));
        m.put("persistedEvidenceKey", null);
        m.put("persistedEvidenceKeys", List.of());
        m.put("involvedCertHashes", involved);
        m.put("pairsExamined", pairs);
        m.put("conflictingPairs", conflicting);
        return GSON.toJson(m);
    }

    public String queryCertificate(String certHash) {
        if (!isReal()) {
            return "{}";
        }
        return withContractString(c -> bytesToString(c.evaluateTransaction("queryCertificate", certHash)));
    }

    public String queryConflicts() {
        if (!isReal()) {
            return "[]";
        }
        return withContractString(c -> bytesToString(c.evaluateTransaction("queryConflicts")));
    }

    public String getAuditHistory(String certHash) {
        if (!isReal()) {
            return "[]";
        }
        return withContractString(c -> bytesToString(c.evaluateTransaction("getAuditHistory", certHash)));
    }

    @FunctionalInterface
    private interface ContractCall {
        FabricSubmitResult run(Contract c) throws Exception;
    }

    @FunctionalInterface
    private interface ContractStringCall {
        String run(Contract c) throws Exception;
    }

    private FabricSubmitResult withContract(ContractCall fn) {
        try (Gateway gw = connect()) {
            Network network = gw.getNetwork(channelName);
            Contract contract = network.getContract(chaincodeId);
            return fn.run(contract);
        } catch (Exception e) {
            log.error("Fabric 调用失败: {}", e.getMessage());
            throw new RuntimeException("Fabric: " + e.getMessage(), e);
        }
    }

    private String withContractString(ContractStringCall fn) {
        try (Gateway gw = connect()) {
            Network network = gw.getNetwork(channelName);
            Contract contract = network.getContract(chaincodeId);
            return fn.run(contract);
        } catch (Exception e) {
            log.error("Fabric 调用失败: {}", e.getMessage());
            throw new RuntimeException("Fabric: " + e.getMessage(), e);
        }
    }

    private static FabricSubmitResult submitParsed(Contract c, String txName, String arg) throws Exception {
        String raw = bytesToString(submitRaw(c, txName, arg));
        return parseSubmitResult(raw);
    }

    private static byte[] submitRaw(Contract c, String txName, String... args) throws Exception {
        Transaction txn = c.createTransaction(txName);
        return txn.submit(args);
    }

    static FabricSubmitResult parseSubmitResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return new FabricSubmitResult("empty-" + UUID.randomUUID(), raw, 0, false);
        }
        try {
            FabricChaincodeTxResponse r = GSON.fromJson(raw, FabricChaincodeTxResponse.class);
            if (r != null && r.getTxId() != null && !r.getTxId().isBlank()) {
                int n = r.getStored() != null ? r.getStored() : 0;
                return new FabricSubmitResult(r.getTxId(), raw, n, false);
            }
        } catch (Exception e) {
            log.debug("链码返回非 JSON，使用占位交易号: {}", e.getMessage());
        }
        return new FabricSubmitResult("legacy-" + UUID.randomUUID(), raw, 0, false);
    }

    private Gateway connect() throws Exception {
        if (connectionProfile == null || connectionProfile.isBlank()) {
            throw new IllegalStateException("未配置 fabric.gateway.connection-profile");
        }
        Gateway.Builder builder = Gateway.createBuilder().networkConfig(Paths.get(connectionProfile));
        builder.discovery(false);
        Path wallet = Paths.get(walletPath);
        Wallet w = Wallet.createFileSystemWallet(wallet);
        ensureWalletIdentity(w);
        builder.identity(w, identityLabel);
        return builder.connect();
    }

    private void ensureWalletIdentity(Wallet wallet) throws Exception {
        if (wallet.exists(identityLabel)) {
            return;
        }
        if (certPath == null || certPath.isBlank() || keyPath == null || keyPath.isBlank()) {
            throw new IllegalStateException("Wallet 中不存在身份且未配置 cert/key");
        }
        try (Reader certReader = Files.newBufferedReader(Paths.get(certPath));
             Reader keyReader = Files.newBufferedReader(Paths.get(keyPath))) {
            Wallet.Identity identity = Wallet.Identity.createIdentity(mspId, certReader, keyReader);
            wallet.put(identityLabel, identity);
        }
    }

    private static String bytesToString(byte[] b) {
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }
}
