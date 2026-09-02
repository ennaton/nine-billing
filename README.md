# nine-billing

[![ci](https://github.com/ennaton/nine-billing/actions/workflows/ci.yml/badge.svg)](https://github.com/ennaton/nine-billing/actions/workflows/ci.yml)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)

Usage metering and a double-entry ledger for [Nine](https://github.com/ennaton/nine-docs). Java 25, Spring Boot, PostgreSQL.

Every other Nine service is about scale and distribution. This one is about **not being wrong**. It turns usage events into charges and writes them to a ledger that refuses to lie: unbalanced, duplicated, mutated or mis-currencied entries never reach disk, and the database is what says no, not application code.

## Status

Alpha. The ledger core and its nine invariants are proven by tests against a real Postgres. Metering (usage event in, priced ledger entry out) and the HTTP surface are in place and tested end to end over HTTP. Reconciliation runs on a schedule and on demand and is proven to catch drift. API-key auth and row-level tenant isolation are in place; the service connects as a non-owner role and every tenant table has a forced policy. The isolation test runs the four assertions from [the RLS article](https://canakyuz.co/blog/multi-tenant-postgres-rls) against this schema as that role.

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

**Reconciliation is a job, and it records clean runs too.** `usage_charges` is what metering believes it charged; `postings` is what the ledger holds. They are written in one transaction, so in theory they cannot disagree. In practice a manual SQL fix, a bypassed trigger or a bug in this service can split them, and the only way to know is to compare. Three set-based queries run every 15 minutes (and on `POST /v1/reconciliation/run`): amount mismatch, orphan charge, unbalanced transaction. Every run is recorded, clean or not, because "we checked and found nothing" is evidence and silence is not. The test for this deliberately corrupts the ledger as a superuser and asserts the drift is reported.

**Two database roles, on purpose.** Flyway migrates as the owner. The service runs as `nine_app`: not a superuser, not the owner of any table, and holding no `UPDATE` or `DELETE` grant on ledger tables at all. Superuser and owner both bypass row-level security, so the runtime role must be neither or every policy is decoration. The immutability tests prove both layers: `nine_app` gets `permission denied` before the trigger is consulted; the owner gets through the grant and is stopped by the trigger.

**Tenant isolation is a test on a reused connection.** Every tenant table has `FORCE ROW LEVEL SECURITY` and a policy on `current_tenant()`, which is `NULLIF(current_setting('app.tenant_id', true), '')::uuid`. The `NULLIF` is load-bearing: after a transaction commits, a custom GUC reverts to the empty string, not to unset, and `''::uuid` would turn the security boundary into a 500. With `NULLIF` it turns into zero rows. The GUC is bound in a `DataSource` wrapper on every connection checkout, not in a helper a repository might forget to call. `TenantIsolationTest` asserts: another tenant sees zero rows, cannot write a row claiming your tenant, no context at all sees zero rows, and no context does not throw.

**Cross-tenant requests are 404, never 403.** The HTTP guard compares the tenant named in the request with the tenant of the key; a mismatch is answered exactly like a missing resource. Existence is not disclosed.

**Reconciliation crosses tenants through a separate operator context**, set only by the job inside its own transaction and reachable from no request. Without it the job would run under RLS with no tenant, see zero rows, and report "clean" forever: the silent failure this whole design exists to avoid.

**API keys are hashed.** SHA-256 of the plaintext is stored; the plaintext is returned once from `POST /admin/keys` (guarded by a bootstrap secret from the environment, outside `/v1`) and never again.

## Run

Needs Docker (for the tests) and **Postgres 15 or later**. A JDK 25 is not required locally:
the build declares the toolchain and Gradle downloads one if the machine has none. The schema sets
`security_invoker` on the balances view, an option that does not exist before 15, so an
older server fails during migration rather than misbehaving at runtime.

```bash
# local Postgres from nine-platform (port 15432, database nine_billing)
(cd ../platform && docker compose up -d postgres)

NINE_BOOTSTRAP_SECRET=dev-bootstrap ./gradlew bootRun   # migrates with Flyway, serves on :18081
./gradlew test             # spins up its own Postgres via Testcontainers
```

Health: `GET /actuator/health`. The key filter denies by default: every request needs
`X-Api-Key` except `/actuator` and `/admin`, and `/admin` carries its own bootstrap-secret
check inside the handler. Mint a key with `POST /admin/keys` and the bootstrap secret
(step 0 in `http/billing.http`).

## Try it

Start the service, then walk the whole API in order:

- **IntelliJ:** open [`http/billing.http`](http/billing.http) and click the green arrow next to each request.
- **Postman:** import [`http/nine-billing.postman_collection.json`](http/nine-billing.postman_collection.json).
- **curl:**

```bash
T=11111111-1111-1111-1111-111111111111
curl -s -X POST localhost:18081/v1/usage -H 'content-type: application/json' \
  -d "{\"eventId\":\"evt-001\",\"tenantId\":\"$T\",\"metric\":\"agent_seconds\",\"quantity\":120}"
# {"transactionId":"...","chargedMinor":240,"currency":"GBP","replayed":false}      201

curl -s localhost:18081/v1/tenants/$T/balance
# {"tenantId":"...","owedMinor":240,"currency":"GBP","display":"2.40 GBP"}
```

Send the same `eventId` again and you get `200`, `replayed: true`, the same `transactionId`, and the balance does not move.

## API

| Method | Path | Does |
|---|---|---|
| `POST` | `/v1/usage` | Price a usage event and post it to the ledger. `201` on first sight, `200` + `replayed: true` on a retry |
| `GET` | `/v1/tenants/{id}/balance` | What the tenant owes (receivable balance) |
| `GET` | `/v1/tenants/{id}/ledger?limit=` | Recent ledger lines, newest first |
| `POST` | `/v1/ledger/{txId}/reverse` | Reverse a transaction. A new transaction; nothing deleted. Second attempt is `409` |
| `POST` | `/admin/keys` | Mint a tenant's API key. Bootstrap secret required. Plaintext shown once |
| `POST` | `/v1/reconciliation/run` | Compare metering against the ledger now; returns the report |
| `GET` | `/v1/reconciliation/runs` | Recent runs with per-kind drift counts and a `clean` flag |
| `GET` | `/v1/reconciliation/runs/{id}/findings` | Every drift found in one run |

Errors are `application/problem+json`: `401` no or bad key, `400` fix the request (a field, a parameter or an unparseable path), `404` no such transaction or no such route, `405` wrong method, `406` unacceptable `Accept`, `409` already done or conflicts, `413` body too large, `415` unsupported content type, `422` well formed but cannot be honored (unknown metric, unbalanced). Two answers are not: an unhandled failure, recorded in [the audit](docs/artifacts/2026-08-28-audit-verification.md), and a method the container refuses before Spring routes it, such as `TRACE`. Both keep Boot's default shape.

Prices live in `price_plans` (V2 migration): `events_ingested`, `agent_seconds`, `seats`. Minor units, GBP.

## Layout

```
src/main/java/co/nine/billing/
  domain/          Money, Posting, LedgerEntry, Direction, AccountType, exceptions
  application/     LedgerService: post (idempotent), reverse, balance
  infrastructure/  LedgerRepository: JDBC against the schema
  metering/        UsageEvent, PricePlan, Charge, MeteringService, MeteringRepository
  api/             BillingController, ApiExceptionHandler (problem+json)
  reconciliation/  ReconciliationService (scheduled + on demand), repository, controller
  auth/            ApiKeyFilter, TenantContext, TenantAwareDataSource, OperatorContext, key bootstrap
src/main/resources/db/migration/
  V1__ledger.sql   accounts, ledger_transactions, postings, triggers, balance view
  V2__metering.sql price_plans, usage_charges
  V3__reconciliation.sql reconciliation_runs, reconciliation_findings
  V4__auth_and_rls.sql   api_keys, nine_app grants, current_tenant(), is_operator(), forced policies
src/test/java/co/nine/billing/
  LedgerInvariantsTest.java   the nine invariants against real Postgres
  MeteringHttpTest.java       the API driven over HTTP, end to end
  ReconciliationTest.java     corrupts the ledger on purpose, asserts the drift is caught
  TenantIsolationTest.java    four RLS assertions as nine_app, plus 401 and 404 at the HTTP layer
  PostgresTestBase.java       one Postgres, two roles: owner migrates, nine_app serves
http/
  billing.http                       IntelliJ HTTP client walkthrough
  nine-billing.postman_collection.json
```

## License

MIT. See [LICENSE](./LICENSE).
