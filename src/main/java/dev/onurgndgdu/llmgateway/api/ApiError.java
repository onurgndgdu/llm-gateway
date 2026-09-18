package dev.onurgndgdu.llmgateway.api;

/**
 * The single error shape callers see, whichever provider failed.
 *
 * @param code stable, machine-readable; callers branch on this, not on the message
 * @param message human-readable, for logs and humans
 * @param providerId which provider failed, when one was reached; null otherwise
 * @param retryable whether retrying this same request could plausibly succeed,
 *                  so a caller does not have to infer it from the status code
 */
public record ApiError(String code, String message, String providerId, boolean retryable) {}
