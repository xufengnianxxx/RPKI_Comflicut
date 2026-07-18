package com.rpki.conflictchecker.dto.fabric;

import com.rpki.conflictchecker.service.fabric.FabricSubmitResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FabricSubmitResponseDto {
    private String txId;
    private String rawResponse;
    private int storedCount;
    private boolean mock;

    public static FabricSubmitResponseDto from(FabricSubmitResult r) {
        if (r == null) {
            return null;
        }
        return FabricSubmitResponseDto.builder()
                .txId(r.txId())
                .rawResponse(r.rawResponse())
                .storedCount(r.storedCount())
                .mock(r.mock())
                .build();
    }
}
