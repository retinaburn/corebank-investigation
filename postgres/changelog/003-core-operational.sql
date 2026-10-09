-- Operational state. Posting orchestration remains a separate application step.
CREATE TABLE core.customer (
    customer_id BIGINT PRIMARY KEY CHECK (customer_id > 0),
    first_name VARCHAR(10) NOT NULL,
    last_name VARCHAR(10) NOT NULL,
    address_line1 VARCHAR(20) NOT NULL,
    city VARCHAR(10) NOT NULL,
    province VARCHAR(2) NOT NULL,
    postal_code VARCHAR(7) NOT NULL,
    country VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE core.account (
    account_id BIGINT PRIMARY KEY CHECK (account_id > 0),
    start_date DATE NOT NULL,
    end_date DATE,
    account_type VARCHAR(8) NOT NULL CHECK (account_type IN ('SAVINGS', 'CHECKING')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (end_date IS NULL OR end_date >= start_date)
);
CREATE TABLE core.relationship (
    account_id BIGINT NOT NULL REFERENCES core.account(account_id),
    customer_id BIGINT NOT NULL REFERENCES core.customer(customer_id),
    type VARCHAR(9) NOT NULL CHECK (type IN ('PRIMARY', 'SECONDARY')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (account_id, customer_id)
);
CREATE INDEX relationship_customer_idx ON core.relationship(customer_id);
CREATE TABLE core.balance (
    account_id BIGINT PRIMARY KEY REFERENCES core.account(account_id),
    balance BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
-- One row per attempt; failed attempts can be retried without erasing their history.
CREATE TABLE core.batch_run (
    batch_run_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_date DATE NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    customer_count BIGINT NOT NULL DEFAULT 0 CHECK (customer_count >= 0),
    account_count BIGINT NOT NULL DEFAULT 0 CHECK (account_count >= 0),
    relationship_count BIGINT NOT NULL DEFAULT 0 CHECK (relationship_count >= 0),
    transaction_count BIGINT NOT NULL DEFAULT 0 CHECK (transaction_count >= 0),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (batch_run_id, batch_date),
    CHECK ((status = 'RUNNING' AND completed_at IS NULL)
        OR (status IN ('COMPLETED', 'FAILED') AND completed_at IS NOT NULL)),
    CHECK (completed_at IS NULL OR completed_at >= started_at),
    CHECK (status = 'FAILED' OR error_message IS NULL)
);
-- At most one live or successful attempt for a date; failed attempts remain historical.
CREATE UNIQUE INDEX batch_run_active_date_idx ON core.batch_run(batch_date)
    WHERE status IN ('RUNNING', 'COMPLETED');
CREATE INDEX batch_run_date_idx ON core.batch_run(batch_date);
CREATE TABLE core.transaction (
    transaction_id BIGINT PRIMARY KEY CHECK (transaction_id > 0),
    account_id BIGINT NOT NULL REFERENCES core.account(account_id),
    type VARCHAR(6) NOT NULL CHECK (type IN ('CR', 'DR')),
    amount BIGINT NOT NULL CHECK (amount >= 0),
    batch_date DATE NOT NULL,
    batch_run_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (batch_run_id, batch_date) REFERENCES core.batch_run(batch_run_id, batch_date)
);
CREATE INDEX transaction_account_date_idx ON core.transaction(account_id, batch_date);
CREATE INDEX transaction_batch_run_idx ON core.transaction(batch_run_id, batch_date);

CREATE FUNCTION core.touch_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.created_at := OLD.created_at;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER customer_updated BEFORE UPDATE ON core.customer
    FOR EACH ROW EXECUTE FUNCTION core.touch_updated_at();
CREATE TRIGGER account_updated BEFORE UPDATE ON core.account
    FOR EACH ROW EXECUTE FUNCTION core.touch_updated_at();
CREATE TRIGGER relationship_updated BEFORE UPDATE ON core.relationship
    FOR EACH ROW EXECUTE FUNCTION core.touch_updated_at();
CREATE TRIGGER balance_updated BEFORE UPDATE ON core.balance
    FOR EACH ROW EXECUTE FUNCTION core.touch_updated_at();
CREATE TRIGGER batch_run_updated BEFORE UPDATE ON core.batch_run
    FOR EACH ROW EXECUTE FUNCTION core.touch_updated_at();

-- No account-creation application service exists yet. Enforce initialization here,
-- atomically with account insertion, including direct SQL callers.
CREATE FUNCTION core.initialize_balance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO core.balance(account_id) VALUES (NEW.account_id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_initial_balance AFTER INSERT ON core.account
    FOR EACH ROW EXECUTE FUNCTION core.initialize_balance();

CREATE FUNCTION core.validate_transaction_account() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    account_end DATE;
BEGIN
    -- Serialize with account closure; a non-key date update must also conflict.
    SELECT end_date INTO account_end FROM core.account
        WHERE account_id = NEW.account_id FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Unknown account %', NEW.account_id USING ERRCODE = '23503';
    END IF;
    IF account_end IS NOT NULL THEN
        RAISE EXCEPTION 'Transactions are not permitted on end-dated account %', NEW.account_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transaction_account_eligibility BEFORE INSERT ON core.transaction
    FOR EACH ROW EXECUTE FUNCTION core.validate_transaction_account();

CREATE FUNCTION core.reject_transaction_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Posted transactions are immutable; use a new reversing transaction'
        USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER transaction_immutable BEFORE UPDATE OR DELETE ON core.transaction
    FOR EACH ROW EXECUTE FUNCTION core.reject_transaction_change();
