package com.github.lisama569.progressivedeliveryjava.api;

import com.github.lisama569.progressivedeliveryjava.domain.FailureReason;
import com.github.lisama569.progressivedeliveryjava.domain.RolloutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常映射：失败原因以机器可读的 reason 字段返回，便于区分。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorBody(String reason, String message) {
    }

    @ExceptionHandler(RolloutException.class)
    public ResponseEntity<ErrorBody> handleRollout(RolloutException ex) {
        HttpStatus status = switch (ex.reason()) {
            case PLAN_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE_SUBMISSION, VERSION_CONFLICT, PLAN_ALREADY_FINISHED ->
                    HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        FailureReason reason = ex.reason();
        return ResponseEntity.status(status)
                .body(new ErrorBody(reason.name(), ex.getMessage()));
    }
}
