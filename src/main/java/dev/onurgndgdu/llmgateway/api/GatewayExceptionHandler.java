package dev.onurgndgdu.llmgateway.api;

import dev.onurgndgdu.llmgateway.provider.ProviderException;
import dev.onurgndgdu.llmgateway.routing.NoRouteException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

/**
 * Translates internal failures into one error shape with a defensible status.
 *
 * <p>Vendors disagree wildly here — the same overload condition arrives as 429
 * from one and 503 from another. Callers should not have to learn each vendor's
 * dialect, so the mapping happens once, in one place.
 */
@RestControllerAdvice
class GatewayExceptionHandler {

    @ExceptionHandler(ProviderException.class)
    ResponseEntity<ApiError> handleProviderFailure(ProviderException exception) {
        HttpStatus status = statusFor(exception.kind());
        ApiError error =
                new ApiError(
                        exception.kind().name(),
                        exception.getMessage(),
                        exception.providerId(),
                        exception.kind().retryable());
        return ResponseEntity.status(status).body(error);
    }

    @ExceptionHandler(NoRouteException.class)
    ResponseEntity<ApiError> handleUnknownModel(NoRouteException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("UNKNOWN_MODEL", exception.getMessage(), null, false));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    ResponseEntity<ApiError> handleInvalidBody(WebExchangeBindException exception) {
        String detail =
                exception.getFieldErrors().stream()
                        .map(error -> "%s %s".formatted(error.getField(), error.getDefaultMessage()))
                        .findFirst()
                        .orElse("request body is invalid");
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", detail, null, false));
    }

    private HttpStatus statusFor(ProviderException.Kind kind) {
        return switch (kind) {
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case CONTENT_FILTERED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            // The caller's credentials are fine; ours are not. Returning 401 here
            // would tell the caller to fix something they do not control, so this
            // is reported as an upstream fault instead.
            case AUTHENTICATION -> HttpStatus.BAD_GATEWAY;
            case TRUNCATED_STREAM, UNKNOWN -> HttpStatus.BAD_GATEWAY;
        };
    }
}
