# nine-billing

[![ci](https://github.com/canakyuz/nine-billing/actions/workflows/ci.yml/badge.svg)](https://github.com/canakyuz/nine-billing/actions/workflows/ci.yml)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)

Usage metering and a double-entry ledger for [Nine](https://github.com/canakyuz/nine-docs). Java 17, Spring Boot, PostgreSQL.

Every other Nine service is about scale and distribution. This one is about **not being wrong**. It turns usage events into charges and writes them to a ledger that refuses to lie: unbalanced, duplicated, mutated or mis-currencied entries never reach disk, and the database is what says no, not application code.

## Status

Pre-alpha. The ledger core is in place and its invariants are proven by tests against a real Postgres. Metering (usage in, charge out) and the query API are next.

## The invariants

Nine rules, each enforced by the database and each attacked by a test in [`LedgerInvariantsTest`](src/test/java/co/nine/billing/LedgerInvariantsTest.java):

| # | Invariant | Enforced by |
|---|---|---|
| 1 | A balanced entry commits and moves both balances | the happy path |
| 2 | An unbalanced entry is refused at COMMIT, even if Java is bypassed | deferred constraint trigger `postings_balance_check` |
| 3 | Replaying an idempotency key returns the original transaction and posts nothing | `UNIQUE (tenant_id, idempotency_key)` |
| 4 | A posting cannot land on an account of another currency | composite FK `(account_id, currency)` |
| 5 | Zero and negative amounts are refused | `CHECK (amount_minor > 0)` and the `Posting` constructor |
| 6 | `UPDATE` on a posting is rejected | `BEFORE UPDATE` trigger |
| 7 | `DELETE` on a transaction is rejected | `BEFORE DELETE` trigger |
| 8 | Reversal is a new transaction pointing at the old one; balance returns to zero, history stays; a transaction can be reversed once | partial unique index on `reverses_id` |
| 9 | 32 concurrent postings to one account leave an exact balance | Postgres transaction isolation, no application locks |

Two of them are checked twice: once in Java so the caller gets a clear error before a round trip, once in Postgres so the guarantee holds when someone bypasses the Java. Both, deliberately.

## Design decisions

**Money is `BIGINT` minor units.** 1234 GBP-minor is 12.34 GBP. Never a floating type anywhere: `0.1 + 0.2` is not `0.3` in binary floating point, and a ledger that cannot add up is not a ledger. The `Money` record enforces this and refuses arithmetic across currencies.

**Nothing is ever updated or deleted.** The ledger is a log. A correction is a reversing transaction that points at what it reverses (`reverses_id`), so the audit trail always shows both the mistake and the fix.

**Balances are derived, never stored.** `account_balances` is a view over postings. A stored balance is a second source of truth that drifts; a view cannot drift because there is nothing to drift from. If it becomes slow, the fix is a materialised rollup with a reconciliation job, not a mutable balance column.

**Idempotency is a unique constraint, not an `if`.** An application-level "does this key exist" check races under concurrency. The constraint does not. A replay surfaces as `DuplicateKeyException`, and the service returns the original transaction id: a retry after a network timeout gets the same answer it would have got the first time.

**Plain JDBC, no ORM.** The guarantees live in the schema. JDBC keeps every statement one step from the constraint that backs it; an ORM would hide the exact moment the database says no.

**Non-superuser at runtime.** [nine-infra](https://github.com/canakyuz/nine-infra) provisions a `nine_app` role that is neither superuser nor table owner, so row-level security applies to it when tenancy policies land.

## Run

Needs a JDK 17 and Docker (for the tests).

```bash
# local Postgres from nine-infra (port 15432, database nine_billing)
(cd ../infra && docker compose up -d postgres)

./gradlew bootRun          # migrates with Flyway, serves on :18081
./gradlew test             # spins up its own Postgres via Testcontainers
```

Health: `GET /actuator/health`.

## Layout

```
src/main/java/co/nine/billing/
  domain/          Money, Posting, LedgerEntry, Direction, AccountType, exceptions
  application/     LedgerService: post (idempotent), reverse, balance
  infrastructure/  LedgerRepository: JDBC against the schema
  api/             HTTP surface (next)
src/main/resources/db/migration/
  V1__ledger.sql   accounts, ledger_transactions, postings, triggers, balance view
src/test/java/co/nine/billing/
  LedgerInvariantsTest.java
```

## License

MIT. See [LICENSE](./LICENSE).
