# Operator is not a GUC

BI12.4, the last open half of D2. Two options for the reconciliation boundary,
what each one costs, and the escape hatch each one leaves. Every claim below was
measured rather than reasoned about.

**Measured on** Postgres 16.15 from `nine-platform`, port 15432, against the real
`nine_billing` database for the hole itself and against a scratch database
`bi124_probe` for the two options. The scratch database and its two roles were
dropped afterwards.

## The hole, in four numbers

`is_operator()` reads `current_setting('app.role')`. `app.role` is a custom
placeholder GUC, and Postgres lets any role set one. So the runtime role decides
for itself whether it is an operator.

Connected as `nine_app`, the role the service runs as, with no privilege beyond
the ones `V4` grants it:

| What was done | `reconciliation_findings` rows |
|---|---|
| Nothing set | 0 |
| The owning tenant bound | 0 |
| `set_config('app.role','operator',false)` | **2, across 1 tenant** |

`nine_app` is `rolsuper=false`, `rolcreaterole=false`, `rolbypassrls=false`. It
holds no grant that could produce this. It simply wrote to the variable that was
supposed to bound it.

The second row is worth its own sentence: binding the tenant that owns those
findings still returns zero, because `findings_operator_only` is
`FOR ALL USING (is_operator())` with no tenant clause at all. The only way to
read the table is to be an operator, and the only thing standing between
`nine_app` and that is one `set_config` call.

## Option A: a separate database role

`is_operator()` becomes `current_user = 'nine_operator'`, and the reconciliation
job connects as that role.

Measured against the probe table, which carries `FORCE ROW LEVEL SECURITY`:

| Attempt from `nine_app` | Result |
|---|---|
| Plain read | 0 rows |
| `set_config('app.role','operator',false)` then read | **0 rows**, the GUC is now inert |
| `SET ROLE nine_operator` | `ERROR: permission denied to set role "nine_operator"` |
| `GRANT nine_operator TO nine_app` | `ERROR: permission denied to grant role`, only ADMIN OPTION may |

**The escape hatch.** One line, `GRANT nine_operator TO nine_app`, and `SET ROLE`
then succeeds and the read returns every row. Measured: after that grant,
`nine_app` reads 2 rows as `current_user=nine_operator`.

That is a real hole but a different kind of hole. `nine_app` cannot open it; only
a migration or a superuser session can, which means it is reviewable, it is in
version control, and it happens once rather than on every request. The reason
somebody would write that line is convenience: running reconciliation in process
on the request pool rather than giving it its own connection. So the escape hatch
is not an accident waiting to happen, it is the shortcut the second connection
pool exists to make unnecessary, and it should be named in the migration comment
so the next person recognises it.

## Option B: a SECURITY DEFINER function

Reconciliation reads go through functions owned by the schema owner, with
`EXECUTE` granted to `nine_app`.

This one has three setups and only the third works, which is the finding:

| Function owner | Table | `nine_app` calling the function |
|---|---|---|
| Not a superuser | `FORCE ROW LEVEL SECURITY` | **0 rows, the function does not work** |
| Superuser | `FORCE ROW LEVEL SECURITY` | 2 rows |
| `BYPASSRLS`, not a superuser | `FORCE ROW LEVEL SECURITY` | 2 rows |

The first row is the one nobody expects. `FORCE` makes the owner subject to the
policies, and `SECURITY DEFINER` runs as the owner, so `FORCE` binds the definer
too and the function returns nothing. Option B does not fail loudly here; it
returns an empty set, which for a reconciliation job reads as "we checked and
found nothing". That is the exact silent failure `V4`'s own comment says the
operator context exists to prevent, arriving through the fix rather than the bug.

So B only works if the owning role bypasses RLS, either by being a superuser or
by holding `BYPASSRLS`.

**The escape hatch.** Once such a role exists and `nine_app` can call functions
it owns, the boundary is no longer the database, it is the signature of every
function that role will ever own. Measured: a second function on the same owner,
`findings_for(tenant text)`, returns another tenant's row to `nine_app` on the
first call. It is four lines, it looks like an ordinary helper, and nothing in
the schema refuses it.

## The decision: Option A

Not because B is unsafe in itself, but because of where the two put the boundary.

A puts it on a role. Roles are checked by the database on every statement, they
cannot be set by the party being bounded, and widening one is a `GRANT` that
shows up in a migration diff.

B puts it on a body of code. Every function that role will ever own is part of
the boundary, forever, and widening it looks like adding a helper. B also
requires handing an RLS bypass to a role whose functions `nine_app` can call,
which is a larger grant than the thing being fixed.

There is a third reason and it is the deciding one. Under A a mistake fails
closed: a job that forgets to use the operator connection reads zero rows and the
reconciliation report is visibly empty. Under B a mistake fails open: a function
with one argument too many hands out another tenant's data and nothing anywhere
reports a problem.

## What A costs, stated plainly

- **A second connection pool.** `TenantAwareDataSource` routes on
  `OperatorContext`; the operator branch needs its own `DataSource` under
  `nine_operator`. Both current callers, the reconciliation job and the key
  bootstrap endpoint, route through it.
- **A second credential.** `V4` already hardcodes `nine_app`'s password, which is
  BI16.3 and open. A second role must not double that debt, so the role is created
  without a password in the migration and the credential is supplied by the
  environment. This makes BI12.4 depend on BI16.3 rather than the reverse, and
  that dependency is real: adding a second literal password now would make the
  later cleanup twice as large.
- **`is_operator()` is load bearing in more places than reconciliation.** `V4:113`
  uses it so the bootstrap endpoint can mint a tenant's first API key. Changing
  the function body changes that path too, so bootstrap moves to the operator
  connection in the same change or it breaks.

## A finding outside this task, from the same measurement

Every table in `nine_billing` is owned by `postgres`, which is a superuser. Seven
of the nine carry `FORCE ROW LEVEL SECURITY`, and `V5`'s comment explains it as
"so the table owner is not exempt either".

Measured as the owner, with no tenant bound:

```
accounts=33  postings=8018  findings=2
```

The same query as `nine_app` returns `0 0 0`.

`FORCE` removes the owner's exemption. It does not remove the superuser's, and
here they are the same role, so `FORCE` currently buys nothing on any of the
seven tables. The intent is written in `CLAUDE.md`, which says the service runs
as a role that is neither superuser nor table owner, but nothing in any migration
creates a non-superuser owner, so no environment the repo can build actually has
one.

This is not BI12.4 and it is not fixed here. It is written down because it was
measured today and because it changes what `FORCE` is worth everywhere in the
schema, which is an argument the next person should not have to rediscover.

## What this does not decide

- **Whether the reconciliation job keeps running in process.** A is compatible
  with either, and the choice belongs with the deployment shape.
- **BI12.3**, the `postings` `WITH CHECK` that binds the account's tenant. It is
  the other half of D2 and independent of this.
- **The owner role.** Named above, not decided. It needs its own measurement of
  what breaks when Flyway stops migrating as a superuser.

## Reproduce

```bash
# the hole, against the real database
docker exec -e PGPASSWORD=nine_app_dev nine-postgres-1 \
  psql -U nine_app -d nine_billing -X -t -A \
  -c "SELECT set_config('app.role','operator',false)" \
  -c "SELECT count(*) FROM reconciliation_findings"

# FORCE against a superuser owner
docker exec -e PGPASSWORD=postgres nine-postgres-1 \
  psql -U postgres -d nine_billing -X -t -A \
  -c "SELECT count(*) FROM accounts"
```

The two options were measured in a scratch database with one table, one
`FORCE ROW LEVEL SECURITY`, and the policy swapped between
`is_operator_guc()` and `current_user = 'nine_operator'`.

## Promote this

This is dated working output and nothing may cite it. BI12.4's acceptance asks
for an ADR, and this is the measurement an ADR would be written from, not the ADR
itself: the decision is one half of a ping-pong task and is not ratified until it
has been through the shared session. When it has, it is rewritten as an ADR in
`nine-docs` and this file is deleted rather than copied.
