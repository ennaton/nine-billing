# Query plans, baseline

BI5.1 to BI5.3. Every query this service runs more than once, measured before any
index is chosen, so that a later change has something to be compared against.

**Environment.** Postgres 16.15 from `nine-platform` on port 15432. Measured as
`nine_app`, the runtime role, with the tenant bound the way a request binds it,
because RLS policies are part of the plan and a measurement taken as `postgres`
would not include them.

**Data.** Generated through the HTTP API rather than seeded with SQL, so every row
went through the real code path and satisfies the ledger triggers. Ten tenants,
four hundred usage events each.

```
accounts              27
ledger_transactions 4005
postings            8010
usage_charges       4005
distinct tenants      13
```

`ANALYZE` was run before measuring. Reconciliation queries were measured with
`app.role = operator`, which is the context the job runs in.

## Numbers

| Query | Planning | Execution | Buffers | Where the time goes |
|---|---|---|---|---|
| BI5.1 `balanceMinor` | 1.831 ms | **1.536 ms** | 33 | Bitmap on `postings_account_idx`, then an RLS subplan |
| BI5.2 `recentLines` | 2.827 ms | **4.455 ms** | 3618 | Per-row RLS subplan, 800 loops |
| BI5.3a `amountMismatches` | 1.312 ms | **6.950 ms** | 12105 | Seq scan by design, then index scan per charge |
| BI5.3b `orphanCharges` | 0.929 ms | **2.444 ms** | 8078 | Anti join, seq scan by design |
| BI5.3c `unbalancedTransactions` | 0.978 ms | **7.467 ms** | 12094 | Seq scan by design, then index scan per transaction |

Reproduce with `EXPLAIN (ANALYZE, BUFFERS)` against the same shape:

```bash
docker exec -e PGPASSWORD=nine_app_dev nine-postgres-1 \
  psql -U nine_app -d nine_billing -X \
  -c "SELECT set_config('app.tenant_id','<uuid>',false)" \
  -c "EXPLAIN (ANALYZE, BUFFERS) SELECT balance_minor FROM account_balances WHERE account_id = '<uuid>'"
```

## Three findings, none of them an index

### 1. The planner misestimates every RLS covered scan by two hundred times

Each of the three reconciliation queries plans for twenty rows and reads four
thousand:

```
Seq Scan on usage_charges c  (cost=0.00..177.14 rows=20 ...) (actual ... rows=4005 ...)
Seq Scan on ledger_transactions t  (cost=0.00..169.14 rows=20 ...) (actual ... rows=4005 ...)
```

The cause is the shape of the policy, not stale statistics. `ANALYZE` had just
run. The predicate reads

```
COALESCE(current_setting('app.role', true), '') = 'operator' OR (hashed SubPlan)
```

and `current_setting` is a black box to the planner: it cannot estimate how many
rows survive, so it falls back to a default guess.

At four thousand rows the error costs nothing, because every plan is fast at this
size. It is written down because the same error at four million rows is what makes
the planner choose a nested loop where a hash join belongs, and that is the
difference between seconds and minutes. **This is the number to watch, and the
threshold to test is a volume where a wrong join order stops being free.**

### 2. `postings` carries no `tenant_id`, so its read policy joins back per row

The policy establishes a posting's tenant through its parent transaction, which
means every posting read runs a subquery against `ledger_transactions`:

```
Index Scan using postings_transaction_idx on postings p (actual ... loops=400)
  Filter: (... OR (SubPlan 1))
  Buffers: shared hit=3600
  SubPlan 1
    ->  Index Scan using ledger_transactions_pkey (actual ... loops=800)
          Buffers: shared hit=2400
```

Two thousand four hundred of `recentLines`'s three thousand six hundred buffers
are the policy checking itself, not the query answering the question.

This is a schema shape, not a missing index. The subplan disappears if `postings`
carries `tenant_id` and the policy compares a column instead of running a
subquery. That is a migration and a policy rewrite, and it belongs in its own
decision rather than folded in here, because denormalising a tenant key onto a
child table has to be argued alongside the constraint that keeps it honest.

### 3. Planning costs more than execution on the hottest read

`balanceMinor` plans in 1.831 ms and executes in 1.536 ms, and planning touches
448 buffers against execution's 33.

This is the balance endpoint, the one a dashboard polls. At one request the ratio
is a curiosity. At a high request rate the service is paying to plan the same
statement over and over, and the answer is a prepared statement or a plan cache
rather than anything in the schema.

## BI5.4: no index is added, and that is the finding

Every join in every plan already uses an index:

```
postings_account_idx          balance view
postings_transaction_idx      recentLines, and both reconciliation joins
ledger_tx_tenant_occurred_idx tenant lookups
ledger_transactions_pkey      the RLS subplan
```

The three reconciliation queries sequentially scan on purpose. They carry no
`WHERE` clause because their job is to check every row; an index cannot improve a
query that is meant to read the whole table.

So the decision recorded here is that **nothing is added**. The measured costs are
a policy shape and a planning cost, and an index fixes neither. Adding one to look
busy would leave a slower write path and an unchanged read path.

The two real candidates are named above, and both are larger than BI5:
denormalising `tenant_id` onto `postings`, and caching the plan for the balance
query. Neither is justified by these numbers at this volume, which is exactly why
the numbers are written down: the next measurement, taken at a volume where the
misestimate stops being free, is what decides them.
