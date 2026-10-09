-- Only successful, atomic four-file ingestion creates a receipt. Existing staging
-- dates must be re-ingested; their completeness cannot safely be inferred.
CREATE TABLE core_ingest.batch_receipt (
    batch_date DATE PRIMARY KEY,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
