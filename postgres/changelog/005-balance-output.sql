-- A header distinguishes an intentionally empty snapshot from a legacy batch
-- completed before snapshots existed. Both header and rows commit with posting.
CREATE TABLE core_output.batch_snapshot (
    batch_date DATE PRIMARY KEY,
    batch_run_id BIGINT NOT NULL UNIQUE,
    record_count BIGINT NOT NULL CHECK (record_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (batch_run_id, batch_date) REFERENCES core.batch_run(batch_run_id, batch_date)
);
CREATE TABLE core_output.balance (
    batch_date DATE NOT NULL REFERENCES core_output.batch_snapshot(batch_date),
    account_id BIGINT NOT NULL CHECK (account_id > 0),
    balance BIGINT NOT NULL,
    PRIMARY KEY (batch_date, account_id)
);
-- Snapshots are historical output, not mutable operational balances.
CREATE FUNCTION core_output.reject_snapshot_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Balance snapshots are immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER snapshot_immutable BEFORE UPDATE OR DELETE ON core_output.batch_snapshot
    FOR EACH ROW EXECUTE FUNCTION core_output.reject_snapshot_change();
CREATE TRIGGER balance_immutable BEFORE UPDATE OR DELETE ON core_output.balance
    FOR EACH ROW EXECUTE FUNCTION core_output.reject_snapshot_change();
