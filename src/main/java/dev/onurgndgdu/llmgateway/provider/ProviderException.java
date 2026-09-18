package dev.onurgndgdu.llmgateway.provider;

/**
 * A vendor failure translated into terms the routing layer can act on.
 *
 * <p>Vendors disagree on how they report the same condition: one returns 429
 * with a header, another a 400 with a code in the body. Adapters map those onto
 * {@link Kind} so that retry and failover policy can be written once.
 */
public class ProviderException extends RuntimeException {

    private final String providerId;
    private final Kind kind;

    public ProviderException(String providerId, Kind kind, String message) {
        this(providerId, kind, message, null);
    }

    public ProviderException(String providerId, Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.providerId = providerId;
        this.kind = kind;
    }

    public String providerId() {
        return providerId;
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        /** Rate limited. Retryable, and a strong signal to route elsewhere. */
        RATE_LIMITED(true),
        /** Provider is down or overloaded. Retryable. */
        UNAVAILABLE(true),
        /** The provider did not answer in time. Retryable. */
        TIMEOUT(true),
        /** The stream ended before a final chunk arrived. Retryable. */
        TRUNCATED_STREAM(true),
        /** The request itself is wrong. Retrying or failing over will not help. */
        INVALID_REQUEST(false),
        /** Credentials rejected. Needs an operator, not a retry. */
        AUTHENTICATION(false),
        /** The provider refused on content grounds. Another provider may differ,
         *  but retrying the same one will not. */
        CONTENT_FILTERED(false),
        /** Unrecognized failure. Treated as non-retryable so that unknown
         *  conditions fail fast instead of amplifying load. */
        UNKNOWN(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
