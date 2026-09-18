package dev.onurgndgdu.llmgateway.api;

import dev.onurgndgdu.llmgateway.provider.LlmProvider;
import dev.onurgndgdu.llmgateway.provider.ProviderException;
import dev.onurgndgdu.llmgateway.provider.mock.MockProvider;
import dev.onurgndgdu.llmgateway.provider.mock.MockScenario;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Exercises the HTTP contract end to end, with every upstream condition driven
 * by the mock provider.
 *
 * <p>The point of these tests is the mapping: a caller must be able to act on
 * the response without knowing which vendor was behind it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(ChatControllerTest.Providers.class)
@TestPropertySource(
        properties = {
            "gateway.mock.enabled=false",
            "gateway.routes.healthy.targets[0].provider-id=stub",
            "gateway.routes.healthy.targets[0].upstream-model=good",
            "gateway.routes.throttled.targets[0].provider-id=stub",
            "gateway.routes.throttled.targets[0].upstream-model=throttled",
            "gateway.routes.down.targets[0].provider-id=stub",
            "gateway.routes.down.targets[0].upstream-model=down",
            "gateway.routes.rejected.targets[0].provider-id=stub",
            "gateway.routes.rejected.targets[0].upstream-model=rejected",
            "gateway.routes.misconfigured.targets[0].provider-id=stub",
            "gateway.routes.misconfigured.targets[0].upstream-model=bad-credentials",
            "gateway.routes.cut-short.targets[0].provider-id=stub",
            "gateway.routes.cut-short.targets[0].upstream-model=cut-short"
        })
class ChatControllerTest {

    @Autowired private WebTestClient client;

    @TestConfiguration(proxyBeanMethods = false)
    static class Providers {
        @Bean
        LlmProvider stubProvider() {
            return new MockProvider("stub")
                    .register("good", MockScenario.replying("hello from the stub"))
                    .register("throttled", MockScenario.failing(ProviderException.Kind.RATE_LIMITED))
                    .register("down", MockScenario.failing(ProviderException.Kind.UNAVAILABLE))
                    .register("rejected", MockScenario.failing(ProviderException.Kind.CONTENT_FILTERED))
                    .register(
                            "bad-credentials", MockScenario.failing(ProviderException.Kind.AUTHENTICATION))
                    .register("cut-short", MockScenario.replying("one two three").truncatedAfter(2));
        }
    }

    @Test
    void returnsCompletionWithUsageAndProviderAttribution() {
        post("healthy")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content")
                .isEqualTo("hello from the stub")
                .jsonPath("$.provider")
                .isEqualTo("stub")
                .jsonPath("$.model")
                .isEqualTo("good")
                .jsonPath("$.usage.totalTokens")
                .isEqualTo(20)
                .jsonPath("$.usage.estimated")
                .isEqualTo(false)
                .jsonPath("$.finishReason")
                .isEqualTo("STOP");
    }

    @Test
    void rateLimitingUpstreamBecomes429AndIsMarkedRetryable() {
        post("throttled")
                .expectStatus()
                .isEqualTo(429)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("RATE_LIMITED")
                .jsonPath("$.retryable")
                .isEqualTo(true)
                .jsonPath("$.providerId")
                .isEqualTo("stub");
    }

    @Test
    void unavailableUpstreamBecomes503() {
        post("down").expectStatus().isEqualTo(503).expectBody().jsonPath("$.retryable").isEqualTo(true);
    }

    @Test
    void contentFilteredBecomes422AndIsNotRetryable() {
        post("rejected")
                .expectStatus()
                .isEqualTo(422)
                .expectBody()
                .jsonPath("$.retryable")
                .isEqualTo(false);
    }

    @Test
    void ourOwnBadCredentialsAreReportedAsAnUpstreamFaultNotA401() {
        // Returning 401 would tell the caller to fix credentials they do not own.
        post("misconfigured").expectStatus().isEqualTo(502);
    }

    @Test
    void unknownAliasIsRejectedAsACallerError() {
        post("no-such-alias")
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("UNKNOWN_MODEL");
    }

    @Test
    void emptyMessageListIsRejectedBeforeAnyProviderIsCalled() {
        client
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "healthy", "messages", java.util.List.of()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("INVALID_REQUEST");
    }

    @Test
    void streamingDeliversChunks() {
        client
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body("healthy"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
    }

    private WebTestClient.ResponseSpec post(String alias) {
        return client
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(alias))
                .exchange();
    }

    private Map<String, Object> body(String alias) {
        return Map.of(
                "model", alias, "messages", java.util.List.of(Map.of("role", "USER", "content", "hi")));
    }
}
