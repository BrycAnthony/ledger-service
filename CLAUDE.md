# Transaction Ledger Service

## Stack
Java 21, Spring Boot 3, PostgreSQL, Flyway migrations,
Testcontainers, Maven, Docker, GitHub Actions

## Domain
Double-entry ledger. Money stored as integer minor units (cents) —
never floating point. Every transfer writes two balanced entries
that sum to zero. Account balances can never go negative.

## Non-negotiables
- Non-negativity enforced by a Postgres CHECK constraint, not app code
- Idempotency: transfers carry an idempotency key with a unique
  constraint. Insert first, catch the duplicate-key violation.
  Never SELECT-then-INSERT. Same key + different params returns 409.
- Lock accounts in deterministic order (sorted by ID) to prevent
  deadlock under concurrent transfers
- Flyway for schema migrations, not JPA auto-DDL

## Conventions
- Testcontainers for all integration tests, real Postgres
- Explain non-obvious decisions in comments