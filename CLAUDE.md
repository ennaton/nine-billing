# nine-billing

Usage metering and a double-entry ledger. Java 17, Spring Boot, PostgreSQL.

## What this repo is for

Correctness. Every other Nine service is about scale; this one is about not being wrong. When a change trades correctness for speed or convenience here, the answer is no.

## Invariants that must never be weakened

The nine ledger invariants are enforced by the database and attacked by `LedgerInvariantsTest`. Weakening one is an architecture change, not a refactor:

1. A balanced entry commits. 2. An unbalanced entry is refused at COMMIT even if Java is bypassed. 3. A replayed idempotency key returns the original transaction. 4. A posting cannot land on an account of another currency. 5. Zero and negative amounts are refused. 6. `UPDATE` on a posting is rejected. 7. `DELETE` on a transaction is rejected. 8. Reversal is a new transaction and happens once. 9. Concurrent postings leave an exact balance.

If you add a table that holds money, it gets the same treatment before it gets an endpoint.

## Decisions that are not up for casual revision

**JDBC, not JPA.** The guarantees live in the schema. JDBC keeps every statement one step from the constraint that backs it; an ORM hides the moment the database says no.

**Money is `BIGINT` minor units.** No floating type touches money anywhere. `Money` refuses arithmetic across currencies.

**Two database roles.** Flyway migrates as the owner; the service runs as `nine_app`, which is neither superuser nor table owner and holds no `UPDATE` or `DELETE` grant on ledger tables. Superuser and owner both bypass row-level security, so the runtime role must be neither. Do not "simplify" this to one role.

**Balances are derived, never stored.** A stored balance is a second source of truth that drifts.

**Reconciliation records clean runs too.** "We checked and found nothing" is evidence; silence is not.

## Testing

Tests run against a real Postgres through Testcontainers, as `nine_app`, so a passing test is a pass under row-level security. An in-memory database would not exercise the triggers, which is where the invariants live. Docker must be running.

`./gradlew test` must be green before a push. CI runs the same suite.

## Rules every Nine repo shares

**Language.** Code, comments, commit messages, docs, artifacts and UI strings are English. No exceptions, including in files nobody reads yet, and including an artifact whose subject was discussed in another language: it is written in English at the moment it is written, not translated afterwards. `githooks/pre-commit` blocks the added lines and the `house-style` CI job scans the whole tree, so a file that predates the rule fails the build until it is rewritten.

**No em dashes.** Commas and colons instead. The pre-commit hook blocks them.

**Commits.** `type(scope): message`, one line, no `Co-Authored-By`, no generator trailers. Enforced by `githooks/commit-msg`.

**Never `--no-verify`.** The hooks are the control, not a suggestion. If a hook is wrong, fix the hook in the same commit.

**Secrets.** Nothing that authenticates anything is committed, ever: keys, tokens, certificates, `.env`, connection strings with an inline password. `githooks/pre-commit` scans the staged diff and refuses. A documented example that trips the scanner ends its line with `nine:allow-secret`; a real value never does.

**After cloning:** `./githooks/install.sh` once, then `brew install gitleaks`.

**Claims carry numbers.** A README that says something is fast links to the run that measured it. No number, no claim.

**Artifacts.** Generated output, a report, a dashboard, an analysis, a plan, a diagram, lands in `docs/artifacts/` inside a repo, and in `artifacts/` in `nine-docs`, where the repo is already docs. Never a repo root, and never the parent `nine/` folder, which is not a repository and therefore not version control. An artifact about one repo lives in that repo. An artifact about more than one lives in `nine-platform/docs/artifacts/`. Files are named `YYYY-MM-DD-subject.ext`, lowercase and hyphenated.

**Write the artifact where it belongs, on the first write.** The path is chosen before the file exists. No scratchpad draft, no `/tmp` staging, no repo root copy that gets moved later. This overrides any assistant default about putting generated files in a temporary directory: here the artifact directory is the working directory, it is version controlled, and an artifact nobody committed did not happen.

**An artifact is not a document.** `nine-docs` holds decisions and measurement reports: authored, reviewed, permanent, and bound by the rules above. An artifact is dated working output that nothing else is allowed to cite. When one earns permanence it is rewritten as an ADR or a report in `nine-docs` and the artifact is deleted, not copied. The same content living in two paths is the failure this rule exists to prevent.
