package com.rpki.conflictchecker.service.fabric;

/**
 * 链码提交结果（链码 JSON 中携带 {@code txId}，便于 Gateway 1.4 客户端解析）。
 */
public record FabricSubmitResult(
        String txId,
        String rawResponse,
        int storedCount,
        boolean mock
) {
    public static FabricSubmitResult mock(String txId, int stored) {
        return new FabricSubmitResult(txId, "{\"mock\":true}", stored, true);
    }
}
