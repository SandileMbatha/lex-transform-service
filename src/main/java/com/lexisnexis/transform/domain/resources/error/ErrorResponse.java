package com.lexisnexis.transform.domain.resources.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Uniform error payload returned by {@link com.lexisnexis.transform.domain.exception.GlobalExceptionHandler}
 * for all failed requests.
 *
 * <p>{@code errorCode} is a machine-readable constant (e.g. {@code VALIDATION_FAILED});
 * {@code errorMessage} is a human-readable description safe to surface to API callers.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private String errorCode;
    private String errorMessage;
}
