-- Metering: usage events priced into ledger entries.
--
-- A usage event carries (tenant, metric, quantity). The price plan says how
-- many minor units one unit of that metric costs. The charge is a ledger
-- transaction: debit the tenant's receivable, credit revenue. The event id is
-- the idempotency key, so a retried event can never be charged twice; the
-- ledger's unique constraint is what guarantees that, not this layer.

CREATE TABLE price_plans (
    metric              TEXT    PRIMARY KEY,          -- e.g. 'events_ingested', 'agent_seconds'
    unit_price_minor    BIGINT  NOT NULL CHECK (unit_price_minor >= 0),
    currency            CHAR(3) NOT NULL,
    description         TEXT    NOT NULL
);

-- Launch prices. Minor units: 1 = 0.01 GBP.
INSERT INTO price_plans (metric, unit_price_minor, currency, description) VALUES
    ('events_ingested', 1,   'GBP', '0.01 GBP per 1000 events, charged per event in thousandths'),
    ('agent_seconds',   2,   'GBP', '0.02 GBP per agent-second of tracked runtime'),
    ('seats',           900, 'GBP', '9.00 GBP per seat per month');

-- Every tenant gets exactly one receivable and one revenue account per
-- currency. Looking them up by (tenant, code) is what the ledger needs.
-- accounts_tenant_code_unique already enforces the uniqueness.

-- A usage record: what was charged, from which event, into which transaction.
-- This is the reconciliation surface: metered quantity on one side, the ledger
-- transaction on the other. If they disagree, the reconciliation job says so.
CREATE TABLE usage_charges (
    event_id        TEXT        NOT NULL,
    tenant_id       UUID        NOT NULL,
    metric          TEXT        NOT NULL REFERENCES price_plans (metric),
    quantity        BIGINT      NOT NULL CHECK (quantity > 0),
    charged_minor   BIGINT      NOT NULL CHECK (charged_minor >= 0),
    currency        CHAR(3)     NOT NULL,
    transaction_id  UUID        NOT NULL REFERENCES ledger_transactions (id),
    occurred_at     TIMESTAMPTZ NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, event_id)
);

CREATE INDEX usage_charges_tenant_occurred_idx ON usage_charges (tenant_id, occurred_at DESC);

-- Same rule as the ledger: a charge is a fact, not a row you edit.
CREATE TRIGGER usage_charges_immutable
    BEFORE UPDATE OR DELETE ON usage_charges
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
