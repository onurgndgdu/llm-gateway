# llm-gateway

A gateway that sits in front of LLM providers and gives application teams one
stable endpoint, while the operational concerns — routing, failover, cost,
caching and rate limiting — are handled in one place instead of being copied
into every service that happens to call a model.

> Work in progress. See [docs/ROADMAP.md](docs/ROADMAP.md) for what is built
> and what is next.

## The problem

Calling an LLM provider directly from an application is straightforward until
it reaches production. Then the same questions surface in every service:

- The provider returns 429 or 503. Do we retry? For how long? Do we fall back
  to another model, and who decides that?
- Spend needs to be attributed per team and capped before the invoice arrives,
  not after.
- The same prompt is answered repeatedly. Nothing caches it, because caching
  belongs to no single service.
- Switching or adding a provider means touching every caller.

These are gateway concerns, not application concerns.

## Architecture

```
client ──▶ gateway ──▶ routing ──▶ provider adapter ──▶ upstream provider
               │
               ├── cache (exact match, then semantic)
               ├── rate limiting and quotas   (Redis)
               ├── cost accounting            (Redis)
               └── metrics                    (Micrometer / Prometheus)
```

Providers sit behind a single SPI. The core never knows which vendor is
answering, which is what makes routing and failover possible at all.

## Design decisions

**Reactive, not servlet-based.** The gateway proxies token streams. With
WebFlux, backpressure from a slow client propagates to the upstream provider
instead of being absorbed by an unbounded buffer in the gateway.

**Mock provider before real providers.** The first adapter is a scripted mock
that can inject latency, failures and truncated streams on demand. Resilience
behaviour is what this project is actually about, and it cannot be tested
reliably against a live provider. It also means the full test suite runs
offline, with no API keys.

**Redis for state.** Counters, quotas and cache entries are short-lived and
shared across instances. A relational store would add durability that none of
this state needs.

## Running

```bash
docker compose up
```

Requires Java 25 and Docker. Integration tests use Testcontainers.

## Stack

Java 25, Spring Boot 4.1, Spring WebFlux, Redis, Resilience4j, Micrometer,
Prometheus, Testcontainers.
