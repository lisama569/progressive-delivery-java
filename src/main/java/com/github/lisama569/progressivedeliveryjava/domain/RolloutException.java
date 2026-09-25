package com.github.lisama569.progressivedeliveryjava.domain;

/**
 * 所有被拒绝操作抛出的异常，携带可区分的失败原因。
 */
public class RolloutException extends RuntimeException {
    private final FailureReason reason;

    public RolloutException(FailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public FailureReason reason() {
        return reason;
    }
}
