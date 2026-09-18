# Roadmap

The gateway is built against a mock provider first. Real providers are adapters
plugged in at the end, which keeps the core provider-agnostic and every test
deterministic and offline.

## Phase 1 — Core proxy path

- [x] Domain model: `ChatRequest`, `ChatResponse`, `Usage` as records, independent of any provider
- [x] `LlmProvider` SPI — one interface, many implementations
- [x] `MockProvider` with scripted scenarios: fixed replies, injected latency, injected failures, truncated streams
- [x] `POST /v1/chat/completions`, non-streaming
- [x] Normalized error model across providers
- [x] SSE streaming passthrough with backpressure
- [x] End-to-end tests with `WebTestClient`

## Phase 2 — Routing and resilience

- [x] Routing rules: model alias to provider and upstream model name
- [x] Fallback chain when the primary provider fails
- [x] Resilience4j: circuit breaker, retry with jitter, per-provider timeouts
- [x] Failure-scenario tests driven by the mock provider

## Phase 3 — Cost and token accounting

- [x] Token accounting, with estimation when the provider returns no usage
- [x] Per-model price table from configuration
- [x] Per-key cost aggregation in Redis
- [x] Budget limits, rejecting with 429 once exceeded
- [x] Micrometer and Prometheus metrics, Grafana dashboard committed to the repo

## Phase 4 — Caching

- [ ] Exact-match cache keyed by request hash, with TTL
- [ ] Semantic cache via embeddings and vector similarity (deterministic local embedder in tests)
- [ ] Cache bypass header and invalidation
- [ ] Hit and miss metrics

## Phase 5 — Access control

- [ ] API key authentication for gateway clients
- [ ] Token bucket rate limiting in Redis, atomic via Lua
- [ ] Per-key quotas
- [ ] Bulkhead and concurrency limits

## Phase 6 — Operations

- [ ] `docker compose up` brings up gateway, Redis, Prometheus and Grafana
- [ ] GitHub Actions: build and test
- [ ] Structured logging with a correlation id
- [ ] Actuator health and readiness probes
- [ ] README: problem, architecture, how to run, design decisions

## Phase 7 — Real providers

- [ ] Anthropic, OpenAI and Ollama adapters, enabled by configuration only

## Out of scope

Prompt management, agent orchestration, fine-tuning and any UI. They belong to
other layers and would only dilute the project.
