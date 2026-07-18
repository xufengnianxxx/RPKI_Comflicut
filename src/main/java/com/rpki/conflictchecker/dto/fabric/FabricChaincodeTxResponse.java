package com.rpki.conflictchecker.dto.fabric;

import lombok.Data;

/**
 * 链码返回的 JSON（含 stub 交易号），由 {@link com.rpki.conflictchecker.service.fabric.FabricBlockchainFacade} 解析。
 */
@Data
public class FabricChaincodeTxResponse {
    private String txId;
    private Integer stored;
    private String message;
}
