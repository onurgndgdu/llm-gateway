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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ResponseCacheIntegrationTest {

    @Autowired private ResponseCache cache;

    private static ChatRequest request(String prompt, Double temperature) {
        return new ChatRequest(
                "fast",
                List.of(new ChatRequest.Message(ChatRequest.Role.USER, prompt)),
                temperature,
                null,
                null);
    }

    private static ChatResponse answer(String content) {
        return new ChatResponse(
                content,
                "mock",
                "mock-fast",
                Usage.reported(10, 5),
                ChatResponse.FinishReason.STOP,
                Duration.ofMillis(900));
    }

    @Test
    void storesAndServesAnIdenticalRequest() {
        ChatRequest request = request("what is the capital of France", 0.0d);
        cache.store("caller-a", request, answer("Paris")).block();

        ChatResponse hit = cache.lookup("caller-a", request).block();

        assertThat(hit).isNotNull();
        assertThat(hit.content()).isEqualTo("Paris");
        assertThat(hit.providerId()).isEqualTo("mock");
    }

    @Test
    void reportsTheLatencyOfThisRequestNotTheOriginalCall() {
        ChatRequest request = request("cached latency", 0.0d);
        cache.store("caller-a", request, answer("stored")).block();

        ChatResponse hit = cache.lookup("caller-a", request).block();

        // The original call took 900ms. Replaying that figure would make the
        // latency metrics worse the better the cache works.
        assertThat(hit.latency()).isEqualTo(Duration.ZERO);
    }

    @Test
    void oneCallersEntryIsNotServedToAnother() {
        ChatRequest request = request("tenant isolation", 0.0d);
        cache.store("caller-a", request, answer("private answer")).block();

        assertThat(cache.lookup("caller-b", request).block()).isNull();
    }

    @Test
    void aDifferentPromptIsADifferentEntry() {
        cache.store("caller-a", request("first question", 0.0d), answer("first")).block();

        assertThat(cache.lookup("caller-a", request("second question", 0.0d)).block()).isNull();
    }

    @Test
    void aRequestThatAskedForVarietyIsNeitherStoredNorServed() {
        ChatRequest varying = request("tell me something", 0.9d);

        cache.store("caller-a", varying, answer("one particular answer")).block();

        // Caching this would quietly turn a request for variety into a fixed
        // answer, and the caller could not tell.
        assertThat(cache.isCacheable(varying)).isFalse();
        assertThat(cache.lookup("caller-a", varying).block()).isNull();
    }

    @Test
    void anUnspecifiedTemperatureIsTreatedAsVarying() {
        // Null means the provider default, which is not necessarily zero.
        assertThat(cache.isCacheable(request("no temperature given", null))).isFalse();
    }

    @Test
    void invalidationRemovesOnlyTheGivenCallersEntries() {
        cache.store("caller-x", request("shared prompt", 0.0d), answer("x answer")).block();
        cache.store("caller-y", request("shared prompt", 0.0d), answer("y answer")).block();

        cache.invalidate("caller-x").block();

        assertThat(cache.lookup("caller-x", request("shared prompt", 0.0d)).block()).isNull();
        assertThat(cache.lookup("caller-y", request("shared prompt", 0.0d)).block()).isNotNull();
    }
}
