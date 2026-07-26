# quant_api

Spring Boot 3 REST API backend for the AI-Driven Equity Signal Platform.

## Overview

Serves daily equity signals, portfolio positions, and news data to `quant_ui`. Publishes signal events to Kafka. All endpoints require Keycloak JWT authentication except `/api/health` and `/api/auth/*`.

## Endpoints

| Controller | Path | Description |
|------------|------|-------------|
| `AuthController` | `POST /api/auth/login` | Exchange credentials for Keycloak tokens |
| `AuthController` | `POST /api/auth/register` | Register a new user via Keycloak admin API |
| `SignalController` | `GET /api/signals/daily` | Latest composite signal scores |
| `PortfolioController` | `GET /api/positions` | Rule-generated paper positions (written by `track_positions.py`) |
| `PortfolioController` | `GET /api/alerts` | Exit-trigger alerts, most recent first |
| `PortfolioController` | `GET /api/performance` | Backtest equity curve and stats |
| `PortfolioController` | `GET /api/paper-performance` | Out-of-sample paper-trading performance |
| `HoldingController` | `GET /api/portfolio/holdings` | The user's own holdings with live prices and totals |
| `HoldingController` | `GET/POST /api/portfolio/transactions` | The trade log behind those holdings |
| `HoldingController` | `PATCH/DELETE /api/portfolio/transactions/{id}` | Correct or remove one trade |
| `HoldingController` | `GET/PUT /api/portfolio/cash` | Cash balance |
| `HoldingController` | `GET /api/portfolio/quote` | Cached single-symbol quote |
| `AgentDataController` | `GET /api/agent-data/*` | News sentiment and feature rows for the research agent |
| `NewsController` | `GET /api/news` | Labeled news articles (MongoDB) |
| `MarketDataController` | `GET /api/market/*` | Price history, OHLCV data |
| `StrategyController` | `GET/POST /api/strategy` | Strategy workflow CRUD |
| `ScriptController` | `POST /api/scripts/run` | Trigger quant_data pipeline scripts |
| `HealthController` | `GET /api/health` | Liveness probe (unauthenticated) |

`/api/positions` and `/api/portfolio/holdings` answer different questions. The first
returns synthetic positions the signal tracker opens mechanically from the daily top-5;
the second returns what the user actually owns, derived from a hand-maintained
transaction log.

### Holdings (P.1)

Transactions are the source of truth and holdings are derived on read — quantity, a
running weighted-average cost, unrealised and realised P&L, and weight as a share of
total capital including cash. Storing an `avgCost` field on a holding row instead would
make it unmaintainable: edit the quantity and there is no way to recompute what the
average should now be. `HoldingService.replay()` is a pure fold over the log, covered by
10 unit tests, because a wrong average cost silently corrupts every downstream figure.

Quotes come from Finnhub behind a 20s TTL cache — the free tier allows 60 requests per
minute and serves one symbol per call, so an unthrottled page would be rate-limited
immediately. Off-hours the TTL stretches to 15 minutes. On failure, an unknown symbol,
or a missing `FINNHUB_API_KEY` the service returns the last `stock_prices_history` close
tagged `source: daily-close` so the caller can label it rather than present a stale
number as live. **Under the VPN, containers have no outbound internet**, so the
containerised service always serves daily closes; live quotes require running on the
host until the quote fetch moves to the host Airflow scheduler (roadmap G.4).

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Framework | Spring Boot 3.3, Java 21 |
| Auth | Spring Security + Keycloak JWT (OAuth2 Resource Server) |
| Primary DB | MySQL 8 (workflow data, strategy configs) |
| Signal/News DB | MongoDB 6 (quant_data database) |
| Messaging | Apache Kafka 3.7 (producer + consumer) |
| Build | Maven |

## Configuration

Key settings in `src/main/resources/application.yml`:

```yaml
server:
  port: 8081

quant:
  kafka:
    signal-topic: quant.signals.daily
  signal:
    top-n: 10              # top N signals per day
  mongo:
    quant-data-uri: ...    # points to quant_data MongoDB database

spring:
  security.oauth2.resourceserver.jwt:
    jwk-set-uri: http://quant_keycloak:8080/realms/quant/protocol/openid-connect/certs
  datasource:
    url: jdbc:mysql://mysql8:3306/workflow
  data.mongodb:
    uri: ...
    database: quantdb
```

## Build & Run

### Local (requires MySQL + MongoDB + Keycloak + Kafka running)

```bash
mvn spring-boot:run
```

### Docker

The CI workflow builds a multi-arch image on every push to `main`:

```bash
mvn clean package -DskipTests
docker build -f Dockerfile.runtime -t xiz001/quant_api:local .
```

Image is deployed via PR to `ai-equity-signal-platform/docker-compose.yml`.

## Security

- All data endpoints require a valid Keycloak JWT (`Authorization: Bearer <token>`)
- JWT is verified against the Keycloak JWKS endpoint (no shared secret)
- `/api/health` is explicitly permitted without auth for Docker healthchecks
- `/api/auth/*` is permitted to allow login/register before token is obtained

## Kafka

The `SignalPublisherScheduler` publishes `DailySignalEvent` objects to `quant.signals.daily` at 09:00 daily. The `SignalConsumerService` listens on the same topic and logs incoming LONG signals.
