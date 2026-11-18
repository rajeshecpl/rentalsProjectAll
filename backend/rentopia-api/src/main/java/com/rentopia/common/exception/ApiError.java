package com.rentopia.common.exception;

import java.time.Instant;
import java.util.List;

/**
 * Standard error body returned by every endpoint.
 *
 * <pre>
 * { "timestamp": "...", "status": 400, "code": "VALIDATION_FAILED",
 *   "message": "...", "path": "/api/v1/...", "fieldErrors": [{"field": "email", "message": "..."}] }
 * </pre>
 */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(Instant.now(), status, code, message, path, null);
    }
}
