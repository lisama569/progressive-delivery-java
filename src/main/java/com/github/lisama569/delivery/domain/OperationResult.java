package com.github.lisama569.delivery.domain;

import java.util.Optional;

/** 操作结果：要么成功携带值，要么失败携带可区分的原因。 */
public record OperationResult<T>(boolean ok, T value, FailureReason reason, String message) {

    public static <T> OperationResult<T> ok(T value) {
        return new OperationResult<>(true, value, null, null);
    }

    public static <T> OperationResult<T> fail(FailureReason reason, String message) {
        return new OperationResult<>(false, null, reason, message);
    }

    public Optional<T> valueOpt() {
        return Optional.ofNullable(value);
    }
}
