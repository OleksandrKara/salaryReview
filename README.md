# salaryReview

Biweekly commission calculator for a nail salon. Replaces a spreadsheet workflow:
enter 4 numbers per provider per pay period (card total, cash total, card tips,
adjustments) and get a WhatsApp-ready salary settlement message.

## Stack

- **Backend:** Spring Boot 4, Java 21, Spring Data JPA, Flyway, PostgreSQL 16
- **Frontend:** Next.js 15 (App Router) + TypeScript + Tailwind *(Day 2)*
- **Orchestration:** Docker Compose

## Quickstart (Day 1: Postgres only — backend runs on host)

```bash
docker compose up -d postgres
cd backend
./mvnw spring-boot:run
```

Health check: `curl localhost:8080/actuator/health`

## Run tests

```bash
cd backend && ./mvnw test
```

## Quickstart (end of Day 2: full stack in Docker)

```bash
docker compose up --build
# open http://localhost:3000
```
