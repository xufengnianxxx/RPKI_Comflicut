package com.rpki.conflictchecker.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private boolean success;
    private int code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data, String message) {
        return Result.<T>builder()
                .success(true)
                .code(200)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> Result<T> ok(T data) {
        return ok(data, "OK");
    }

    public static <T> Result<T> fail(int code, String message) {
        return Result.<T>builder()
                .success(false)
                .code(code)
                .message(message)
                .build();
    }
}
