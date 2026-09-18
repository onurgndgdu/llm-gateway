package dev.onurgndgdu.llmgateway.cost;

import dev.onurgndgdu.llmgateway.provider.ChatRequest;
import dev.onurgndgdu.llmgateway.provider.Usage;
import org.springframework.stereotype.Component;

/**
 * Fills in token counts when a provider does not report them.
 *
 * <p>The estimate is deliberately crude: roughly four characters per token,
 * which is the well-known rule of thumb for English text and wrong for
 * everything else. It exists so that an unreported call still appears in cost
 * figures rather than silently counting as free, which is the failure mode
 * that matters — a provider that reports nothing would otherwise look like the
 * cheapest one in every report.
 *
 * <p>Estimates are marked as such all the way through, so no report can present
 * a guess as a measurement.
 */
@Component
public class TokenEstimator {

    private static final int CHARS_PER_TOKEN = 4;

    /** Per-message overhead for role markers and separators. */
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;

    public Usage estimate(ChatRequest request, String completion) {
        int promptTokens =
                request.messages().stream()
                        .mapToInt(message -> tokensIn(message.content()) + MESSAGE_OVERHEAD_TOKENS)
                        .sum();

        return Usage.estimated(promptTokens, tokensIn(completion));
    }

    /** Returns the reported usage, or an estimate when there is none. */
    public Usage resolve(ChatRequest request, String completion, Usage reported) {
        return reported != null ? reported : estimate(request, completion);
    }

    private int tokensIn(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.ceilDiv(text.length(), CHARS_PER_TOKEN);
    }
}
