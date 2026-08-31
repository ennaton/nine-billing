# nine-billing

Usage metering and a double-entry ledger. Java 25, Spring Boot, PostgreSQL.

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

**Language.** Code, comments, commit messages, docs, artifacts and UI strings are English. One exception, named rather than left to judgement, and the rest of the sentence holds without it: including in files nobody reads yet, and including an artifact whose subject was discussed in another language, which is written in English at the moment it is written and not translated afterwards. `githooks/pre-commit` blocks the added lines and the `house-style` CI job scans the whole tree, so a file that predates the rule fails the build until it is rewritten.

**The working board is the exception, and it has to live under version control.** `nine-calisma-panosu.md` is the one document the two of us plan in rather than publish, and the planning happens in Turkish. Holding it to the rule above is what kept it outside every repository, and the cost of that was measured on 31 August: a hundred and fifty task rows, every status, every assignment and every dated measurement, in a single untracked file with no history and no copy anywhere. A rule that pushes the most important document out of version control is the rule that is wrong, not the document. So the board may be Turkish, and it may not be outside a repository. Nothing else is exempt: a rule with one named exception is still a rule, and a rule with a category of exceptions is not.

**No em dashes.** Commas and colons instead. The pre-commit hook blocks them.

**Commits.** `type(scope): message`, one line, no generator trailers. Enforced by `githooks/commit-msg`.

**Co-authorship is for people, and on shared work it is not optional.** A commit two of you wrote carries `Co-Authored-By` for the other one. A ping-pong group hands a file back and forth and the commit lands under whoever happened to be holding it, so without the trailer half the work is invisible in the only place it gets counted. **A task the board marks as shared is not finished without the trailer**: whoever held the keyboard names the other one, in both directions and without being asked, because on a shared task the person who did not commit did half of it. The trailer goes in its own block at the end, after a blank line, and the address has to be one GitHub already knows for that person or no credit is applied. A tool is not an author: the same hook that allows the human trailer refuses one naming Claude, Copilot or a bot.

**Never `--no-verify`.** The hooks are the control, not a suggestion. If a hook is wrong, fix the hook in the same commit.

**Secrets.** Nothing that authenticates anything is committed, ever: keys, tokens, certificates, `.env`, connection strings with an inline password. `githooks/pre-commit` scans the staged diff and refuses. A documented example that trips the scanner ends its line with `nine:allow-secret`; a real value never does.

**After cloning:** `./githooks/install.sh` once, then `brew install gitleaks`.

**Claims carry numbers.** A README that says something is fast links to the run that measured it. No number, no claim.

**Prose carries its mechanism.** A sentence explaining why something works is a claim, and it carries the same burden as a number. A comment reading "this handler wins because it is declared first" was wrong: Spring ranks candidates with `ExceptionDepthComparator` and position in the file means nothing. Checking produced the stronger sentence, that nobody can break the distinction by reordering the methods. If the mechanism was not checked, the sentence does not get written.

**Artifacts.** Generated output, a report, a dashboard, an analysis, a plan, a diagram, lands in `docs/artifacts/` inside a repo, and in `artifacts/` in `nine-docs`, where the repo is already docs. Never a repo root, and never the parent `nine/` folder, which is not a repository and therefore not version control. An artifact about one repo lives in that repo. An artifact about more than one lives in `nine-platform/docs/artifacts/`. Files are named `YYYY-MM-DD-subject.ext`, lowercase and hyphenated.

**Write the artifact where it belongs, on the first write.** The path is chosen before the file exists. No scratchpad draft, no `/tmp` staging, no repo root copy that gets moved later. This overrides any assistant default about putting generated files in a temporary directory: here the artifact directory is the working directory, it is version controlled, and an artifact nobody committed did not happen.

**An artifact is not a document.** `nine-docs` holds decisions and measurement reports: authored, reviewed, permanent, and bound by the rules above. An artifact is dated working output that nothing else is allowed to cite. When one earns permanence it is rewritten as an ADR or a report in `nine-docs` and the artifact is deleted, not copied. The same content living in two paths is the failure this rule exists to prevent.

**A change is finished when what it unblocks is stated.** The acceptance criterion is where the work stops, not where the thinking stops. `findAccount` selected on `(tenant, code)` and took the first row with no `ORDER BY`, which was correct until the change that widened that exact key, written by the same hand in the same week. Before closing anything, answer in writing: what is now true that was not, and who is waiting on it. If nothing is unblocked, say so, because silence reads the same as not having looked. This rule and the two around it came from a run of defects with one shape in common: every statement behind them was individually true, and none was carried to its consequence.

**The edit is not the boundary of what you read.** Read the whole method you are touching, not the line you came for. Two defects found in one review sat within five lines of a change that was itself correct: a balance response took its number from the computed money and its label from the request string, and the two agreed only because nothing had yet made them disagree.
