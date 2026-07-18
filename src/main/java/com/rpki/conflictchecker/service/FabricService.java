package com.rpki.conflictchecker.service;

import com.google.gson.Gson;
import com.rpki.conflictchecker.dto.ConflictResult;
import com.rpki.conflictchecker.entity.FabricTxRecord;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.fabric.gateway.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

@Slf4j
@Service
public class FabricService {

    private static final Gson GSON = new Gson();

    @Autowired
    private StorageService storageService;

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

    public String submitConflictToChain(ConflictResult conflictResult) {
        return "REAL".equalsIgnoreCase(mode) ? submitReal(conflictResult) : submitMock(conflictResult);
    }

    private String submitMock(ConflictResult result) {
        String txId = "mock-tx-" + System.currentTimeMillis();
        saveTx(result, txId, "MOCK", "SUCCESS", null);
        return txId;
    }

    private String submitReal(ConflictResult result) {
        try {
            Gateway.Builder builder = Gateway.createBuilder().networkConfig(Paths.get(connectionProfile));
            // 关闭服务发现，避免 connection profile 中 peer 主机名在本机无法解析。
            builder.discovery(false);
            Path wallet = Paths.get(walletPath);
            Wallet w = Wallet.createFileSystemWallet(wallet);
            ensureWalletIdentity(w);
            builder.identity(w, identityLabel);
            try (Gateway gateway = builder.connect()) {
                Network network = gateway.getNetwork(channelName);
                Contract contract = network.getContract(chaincodeId);
                String payload = GSON.toJson(result);
                byte[] response = contract.submitTransaction("DetectAndRecord", payload);
                String txId = new String(response);
                saveTx(result, txId, "REAL", "SUCCESS", null);
                return txId;
            }
        } catch (Exception e) {
            saveTx(result, null, "REAL", "FAILED", e.getMessage());
            throw new RuntimeException("Fabric REAL 模式提交失败: " + e.getMessage(), e);
        }
    }

    private void ensureWalletIdentity(Wallet wallet) throws Exception {
        if (wallet.exists(identityLabel)) {
            return;
        }
        if (certPath == null || certPath.isBlank() || keyPath == null || keyPath.isBlank()) {
            throw new IllegalStateException("Wallet 中不存在身份且未配置 cert/key 导入路径");
        }
        try (Reader certReader = Files.newBufferedReader(Paths.get(certPath));
             Reader keyReader = Files.newBufferedReader(Paths.get(keyPath))) {
            Wallet.Identity identity = Wallet.Identity.createIdentity(mspId, certReader, keyReader);
            wallet.put(identityLabel, identity);
            log.info("已导入 Fabric 身份到 wallet, label={}", identityLabel);
        }
    }

    private void saveTx(ConflictResult result, String txId, String mode, String status, String error) {
        FabricTxRecord record = FabricTxRecord.builder()
                .conflictRecordId(null)
                .mode(mode)
                .txId(txId)
                .blockNum(null)
                .payload(GSON.toJson(result))
                .status(status)
                .errorMessage(error)
                .createdAt(LocalDateTime.now())
                .build();
        storageService.saveFabricTx(record);
    }
}
