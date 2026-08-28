# Audit verification, 28 August 2026

An independent replay of the security and correctness audit written against
`build/spring-boot-4.1 @ 64b76dc`, now merged as `d831b21`. The audit tagged part
of its findings "Reported", meaning reviewed but not re-run. This note records
which findings were reproduced here, with what output, and what the blast radius
of each fix is.

Nothing in the repository was modified while measuring. The one schema change
made during testing was reverted.

**Environment.** Spring Boot 4.1.1, JDK 17.0.19 Temurin, Postgres 16 from
`nine-platform` on port 15432, Docker 29.5.3. The audit ran on Docker 29.7.2, so
the Testcontainers result below holds on two different daemons.

## Baseline

`./gradlew test` after the merge: 35 tests, 0 failures, 0 errors, 0 skipped.
Execution time summed from the JUnit reports is 2.59s. CI on `main` after the
merge is green for both the `ci` and `secret-scan` jobs.

Compiler warnings under `-Xlint:all`: 52, all in test sources, 22 `rawtypes Map`,
1 `rawtypes List`, and 29 `unchecked` across three categories. Zero deprecation
and zero removal warnings.

## Reproduced

### 1. The balances view bypasses row level security

`account_balances` is created without `security_invoker`, and its owner is a
superuser, so it reads its base tables as the owner regardless of `FORCE ROW
LEVEL SECURITY`.

```
account_balances  owner=postgres  reloptions=(none)
postgres  super=true   bypassrls=true
nine_app  super=false  bypassrls=false

tenant bound:      accounts TABLE = 2      account_balances VIEW = 7
no tenant bound:   accounts = 0   ledger_transactions = 0   account_balances = 7
```

The third line is the fail-closed case that `TenantIsolationTest` asserts on
three tables and not on the view.

**Blast radius of the fix.** `account_balances` has exactly one reader in the
whole codebase, `LedgerRepository:84`. Reconciliation does not touch it: it
queries `usage_charges`, `postings` and `ledger_transactions` directly. So
setting `security_invoker` cannot break the operator path. The only affected
call chain is the balance endpoint, which already runs with a tenant bound.

### 2. The application role can promote itself to operator

`is_operator()` reads the `app.role` GUC, and any session can set it.

```
as nine_app, no tenant bound:
  set_config('app.role','operator')  ->  is_operator = true
  accounts = 7        usage_charges = 5
```

Reachable only through SQL that an attacker influences. The same audit confirms
every query in `src/main` is parameterised, so this is defence in depth rather
than a live path. It still matters, because the stated purpose of the two role
design is to survive a bug above the database.

### 3. One GET permanently exempts a tenant from billing

`currency` is an unvalidated query parameter that reaches the `ensureAccount`
INSERT before `Money.of` validates it, and the account key is
`(tenant_id, code)`, so the first currency wins forever.

```
fresh tenant, its own key:
  GET  /v1/tenants/{T}/balance?currency=USD   200
  POST /v1/usage  agent_seconds x120          409  postings_currency_matches_account
  POST /v1/usage  agent_seconds x5            409  permanent

control tenant, no ?currency:
  GET  balance                                200  GBP
  POST /v1/usage                              201  chargedMinor 240
```

`nine_app` holds no UPDATE or DELETE grant on `accounts` and the upsert is
`DO NOTHING`, so no code path repairs the row.

On an existing tenant the same parameter is quieter and worse. The audit called
this a relabel. It is larger than that:

```
GET /v1/tenants/{T}/balance?currency=JPY
  -> {"owedMinor":240,"currency":"JPY","display":"240 JPY"}
```

The real debt is 2.40 GBP. JPY reports zero fraction digits, so `display()`
skips the divide and prints the minor units whole. The response overstates the
amount by a factor of one hundred, with a 200.

Root cause is not the missing validation. `MeteringService.owed` performs an
INSERT from inside a GET handler, and is not even transactional. Validation
closes the symptom; moving the write out of the read path closes the class.

### 4. A path parameter walks past the API key filter

`shouldNotFilter` decides on `getRequestURI()`, which keeps path parameters.
Spring routes on the parsed path, which does not.

```
no X-Api-Key on any of these:
  GET /v1/reconciliation/runs        401
  GET /v1;x=1/reconciliation/runs    200   full run history
  GET /v1;a=b/reconciliation/runs    200
  GET /v1;x=1/tenants/{T}/balance    500   fails closed, but not problem+json
```

The same line has a second trigger with no attacker involved. Set a servlet
context path, or put the service behind an ingress that adds a prefix, and
`startsWith("/v1/")` is false for every request, so the filter disables itself
service wide.

**API check for the fix.** Listed against `spring-web-7.0.9.jar` rather than
recalled. `ServletRequestPathUtils.parse(HttpServletRequest)` is static and does
not require the DispatcherServlet cache, which matters because a filter runs
first, so `parse` is correct and `getParsedRequestPath` is not.
`RequestPath.pathWithinApplication()` strips the context path, and
`PathContainer.PathSegment` exposes `valueToMatch()` and `parameters()` as
separate methods, so path parameters are outside the matched value. One call
closes both triggers. Trimming the raw URI at the semicolon closes only the
first.

### 5. Idempotency returns 500 under concurrency

`MeteringService.charge` is transactional, the unique key aborts the
transaction, and `LedgerService.post` then runs its recovery SELECT on the same
aborted connection.

```
50 concurrent POST /v1/usage, identical eventId:
   1 x 201      the winner
  44 x 200      correct replays via the fast path
   5 x 500      should have been 200 with the original transaction

ledger after the run:  tx_count = 1   charge_count = 1   distinct_tx = 1
```

The ledger stayed correct. Only the API lied. The audit measured 9 of 50 rather
than 5, which is timing, not a different defect. The 500 body is Boot's default
error shape, not `application/problem+json`.

### 6. Errors and actuator

Reproduced while measuring the above rather than as separate tests.

```
409 body carries raw Postgres text:
  "ERROR: insert or update on table \"postings\" violates foreign key
   constraint \"postings_currency_matches_account\""

500 body:  {"timestamp","status","error","path"}   not problem+json

no X-Api-Key:  /actuator/health 200   /actuator/info 200   /actuator/metrics 200
```

## Confirmed by reading, not run

- `postings` policy `WITH CHECK` binds only the parent transaction, never the
  posting's account tenant. Foreign key checks bypass RLS by design, so the
  account does not have to be visible to be referenced.
- The balance trigger sums across currencies with no `GROUP BY currency`, so a
  debit and a credit in two currencies net to zero. Reaching it requires two
  accounts in different currencies, which finding 3 is a way to create.
- `TenantAwareDataSource.bind` takes a connection and then runs `set_config`.
  If that statement throws, the connection is never closed and Hikari does not
  reclaim an in-use connection.
- `PricePlan.priceFor` uses `Math.multiplyExact` and `quantity` has `@Min(1)`
  with no upper bound. The resulting `ArithmeticException` maps to no 4xx.
- `ApiExceptionHandler` does not extend `ResponseEntityExceptionHandler`, so
  framework exceptions land on the default error controller.
- The runtime role password is created by `V4` and repeated as the default in
  `application.yml`, and the gitleaks allowlist lists it as a placeholder.

## One correction to the audit

The audit heads a finding "Invariant 5 has no database half". The `CHECK
(amount_minor > 0)` does exist, at `V1__ledger.sql:66`. The body of the finding
is accurate: only the Java constructor is attacked by the suite, so relaxing the
CHECK leaves 35 tests green. That is a test coverage gap, not a missing
constraint, and the heading reads as the second.

## Work breakdown

Seven groups, 24 tasks, tracked on the planning board. Blockers first, ordered
by live exposure times cost rather than by severity label.

| Group | Covers | Owner split |
|---|---|---|
| BI11 Authentication boundary | filter path, deny by default, actuator | Kemal 2, Can 1 |
| BI12 The database half of RLS | view, balanceMinor, postings WITH CHECK, operator role | Can 2, Kemal 1, both 1 |
| BI13 Currency lock-in | validation, GET side effect, account key, reported currency | Can 2, Kemal 2 |
| BI14 Idempotency fallback | conflict path, replay price, reversal key | Can 1, Kemal 2 |
| BI15 Error contract | framework errors, raw database text, quantity bound | Can 2, Kemal 1 |
| BI16 Silent failure | reconciliation visibility, connection leak, role password | Can 1, Kemal 2 |
| BI17 Test suite blind spots | invariant 5, real contention, isolation, finally | both 4 |

BI11 through BI14 are the blockers and belong in the first two weeks. BI15
through BI17 follow. The idempotency 500 is scheduled with BI3, which already
exists on the board and will surface it with a test.

Suggested first two, both small and both with a verified blast radius: the view
option on BI12.1, and the filter path on BI11.1. Neither is complete without its
regression test. The fail-closed assertion in `TenantIsolationTest:99` counts
three tables today; the view is the fourth.
