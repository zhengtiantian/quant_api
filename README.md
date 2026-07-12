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
| `PortfolioController` | `GET /api/portfolio` | Open paper-trading positions |
| `NewsController` | `GET /api/news` | Labeled news articles (MongoDB) |
| `MarketDataController` | `GET /api/market/*` | Price history, OHLCV data |
| `StrategyController` | `GET/POST /api/strategy` | Strategy workflow CRUD |
| `ScriptController` | `POST /api/scripts/run` | Trigger quant_data pipeline scripts |
| `HealthController` | `GET /api/health` | Liveness probe (unauthenticated) |

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
