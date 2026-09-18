package dev.onurgndgdu.llmgateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import dev.onurgndgdu.llmgateway.TestcontainersConfiguration;
import dev.onurgndgdu.llmgateway.provider.ChatRequest;
import dev.onurgndgdu.llmgateway.provider.ChatResponse;
import dev.onurgndgdu.llmgateway.provider.Usage;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties = {
            "gateway.cache.semantic-enabled=true",
            // Low enough that word overlap alone can clear it, since the local
            // embedder captures overlap rather than meaning.
            "gateway.cache.similarity-threshold=0.80",
            "gateway.cache.max-semantic-entries=5"
        })
class SemanticCacheIntegrationTest {

    @Autowired private SemanticCache cache;

    private static ChatRequest ask(String prompt) {
        return new ChatRequest(
                "fast", List.of(new ChatRequest.Message(ChatRequest.Role.USER, prompt)), 0.0d, null, null);
    }

    private static ChatResponse answer(String content) {
        return new ChatResponse(
                content, "mock", "mock-fast", Usage.reported(10, 5),
                ChatResponse.FinishReason.STOP, Duration.ofMillis(800));
    }

    @Test
    void servesAnAnswerToANearIdenticalQuestion() {
        cache.store("caller-1", ask("how do I reset my password"), answer("Use the reset link")).block();

        SemanticCache.Match match =
                cache.lookup("caller-1", ask("how can I reset my password")).block();

        assertThat(match).isNotNull();
        assertThat(match.response().content()).isEqualTo("Use the reset link");
        assertThat(match.score()).isGreaterThanOrEqualTo(0.80d);
    }

    @Test
    void doesNotServeAnAnswerToAnUnrelatedQuestion() {
        cache.store("caller-2", ask("how do I reset my password"), answer("Use the reset link")).block();

        assertThat(cache.lookup("caller-2", ask("what is the refund policy for annual plans")).block())
                .isNull();
    }

    @Test
    void carriesTheScoreSoAWrongHitCanBeDiagnosed() {
        cache.store("caller-3", ask("deploy the service to staging"), answer("Run the pipeline")).block();

        SemanticCache.Match match =
                cache.lookup("caller-3", ask("deploy the service to staging")).block();

        // An identical question scores 1.0; anything lower is a judgement the
        // threshold made, and the score is what makes it reviewable.
        assertThat(match.score()).isCloseTo(1.0d, org.assertj.core.data.Offset.offset(0.0001d));
    }

    @Test
    void oneCallersIndexIsNotVisibleToAnother() {
        cache.store("caller-4", ask("internal runbook question"), answer("internal answer")).block();

        // A semantic hit returns an answer produced for a different prompt.
        // Across tenants that is someone else's content, not merely their cache.
        assertThat(cache.lookup("caller-5", ask("internal runbook question")).block()).isNull();
    }

    @Test
    void theIndexStaysBounded() {
        for (int i = 0; i < 12; i++) {
            cache.store("caller-6", ask("question number " + i), answer("answer " + i)).block();
        }

        // Configured limit is 5. Without trimming, the linear scan that makes
        // this design viable would grow without bound.
        assertThat(cache.indexSize("caller-6").block()).isLessThanOrEqualTo(5L);
    }
}
