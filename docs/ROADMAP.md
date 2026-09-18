# Roadmap

The gateway is built against a mock provider first. Real providers are adapters
plugged in at the end, which keeps the core provider-agnostic and every test
deterministic and offline.

## Phase 1 — Core proxy path

- [ ] Domain model: `ChatRequest`, `ChatResponse`, `Usage` as records, independent of any provider
- [ ] `LlmProvider` SPI — one interface, many implementations
- [ ] `MockProvider` with scripted scenarios: fixed replies, injected latency, injected failures, truncated streams
- [ ] `POST /v1/chat/completions`, non-streaming
- [ ] Normalized error model across providers
- [ ] SSE streaming passthrough with backpressure
- [ ] End-to-end tests with `WebTestClient`

## Phase 2 — Routing and resilience

- [ ] Routing rules: model alias to provider and upstream model name
- [ ] Fallback chain when the primary provider fails
- [ ] Resilience4j: circuit breaker, retry with jitter, per-provider timeouts
- [ ] Failure-scenario tests driven by the mock provider

## Phase 3 — Cost and token accounting

- [ ] Token accounting, with estimation when the provider returns no usage
- [ ] Per-model price table from configuration
- [ ] Per-key cost aggregation in Redis
- [ ] Budget limits, rejecting with 429 once exceeded
- [ ] Micrometer and Prometheus metrics, Grafana dashboard committed to the repo

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
