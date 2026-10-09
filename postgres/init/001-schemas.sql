-- Runs only when the official image initializes an empty data volume.
-- Business tables will be added once their layouts are finalized.
BEGIN;
CREATE SCHEMA core_ingest;
CREATE SCHEMA core;
CREATE SCHEMA core_output;
CREATE SCHEMA camel_ingest;
COMMIT;
