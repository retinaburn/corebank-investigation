# Corebank Investigation

A Java banking batch and Apache Camel integration learning project.

## Status

Java 21 / Spring Boot core launched with JBang, YAML configuration, banking records, and tested Spring Batch readers for all four input files. PostgreSQL staging ingestion is implemented for all four files. Operational tables, audit timestamps, account balance initialization, and batch-attempt tracking are defined through Liquibase. Business validation and operational posting remain to come.

See [the design document](docs/banking-core-design.md) for agreed requirements and open decisions.

## Layout

- `corebank/`: Java banking core.
- `camel/`: future Camel routes developed with JBang and Camel MCP.
- `postgres/`: database schemas and migrations.
- `docker/`: Docker Compose and container configuration.
- `data/input/`: fixed-width batch inputs and triggers.
- `data/output/`: balance output files.
- `data/error/`: error artifacts.
- `docs/`: design and file specifications.

Runtime data is ignored by Git; directory placeholders are tracked. Docker Compose is the initial planned runtime.

## PostgreSQL

See [PostgreSQL setup](postgres/README.md) for startup, connection, and persistence details.

## Run and test the core

With PostgreSQL running and `COREBANK_DB_PASSWORD` (or its fallback,
`POSTGRES_PASSWORD`) set in your environment, from the repository root:

```sh
jbang corebank/src/Core.java
jbang corebank/tests/ReaderTests.java
```

All four file readers and their support classes live in `corebank/src/corebank/readers/`
(package `corebank.readers`). The readers are reusable components, not startup ingestion jobs.
Create it with `CustomerFileReader.create(path)`, open it with a Spring Batch
`ExecutionContext`, read until it returns null, and close it in a finally block.
All readers use 19-position IDs and enforce values from 1 through Long.MAX_VALUE.
Use AccountFileReader.create(path) for the 47-position account format.

RelationshipFileReader.create(path) reads 47-position records;
TransactionFileReader.create(path) reads 64-position records, including a
20-position nonnegative-cent amount. All amounts must fit in a Java long.
The reader suite currently contains 62 tests.

## Stage a batch in PostgreSQL

Start PostgreSQL and set
`COREBANK_DB_PASSWORD` to your local database password (or set `POSTGRES_PASSWORD`
as its fallback). Optional overrides:
`COREBANK_DB_URL` (default `jdbc:postgresql://localhost:5432/corebank`) and
`COREBANK_DB_USER` (default `postgres`). JBang does not automatically read docker/.env.

From the repository root:

```sh
jbang corebank/src/Core.java --ingest=2026-10-09
```

This requires customer_20261009.dat, account_20261009.dat,
relationship_20261009.dat, and transaction_20261009.dat in data/input.
Override the directory with `--corebank.input-directory=/path/to/input`.
All four files must exist; empty files are accepted. Publish complete files before
running and do not modify them during ingestion. The command exits after loading.
Liquibase runs automatically before ingestion. Running without `--ingest` connects
to PostgreSQL, applies pending migrations, and exits without loading files.
See [database migration setup](postgres/README.md#liquibase-migrations).

Each row in core_ingest records batch_date, source_file, and source_line.
All four loads commit together; any parsing or database failure rolls back the load.
A rerun atomically replaces that date's staged rows and retains other dates.
Same-date loads are serialized using a PostgreSQL transaction advisory lock.
JDBC inserts execute in groups of 500 within one transaction.
Duplicate business keys are retained for future validation, not silently overwritten.
This command only stages data: trigger-file watching, cross-record validation,
core-table updates, balance posting, and output generation remain future steps.

### Database integration tests

Use the temporary PostgreSQL instance defined in `docker/compose.test.yaml`.
It exposes database `corebank_test` on `localhost:55432`, with user `postgres`
and password `test_only`. From the repository root:

```sh
export COREBANK_TEST_DB_URL=jdbc:postgresql://localhost:55432/corebank_test
export COREBANK_TEST_DB_USER=postgres
export COREBANK_TEST_DB_PASSWORD=test_only

cd docker
docker compose -f compose.test.yaml up -d --wait
jbang ../corebank/tests/IngestionTests.java
docker compose -f compose.test.yaml down
```

Run the final `down` command even if the tests fail. The database uses temporary
in-memory storage; its contents are discarded when the container stops.

The test launcher applies the same Liquibase changelog before running tests.
It reads `COREBANK_TEST_DB_URL`, `COREBANK_TEST_DB_USER` (defaults to `postgres`),
and `COREBANK_TEST_DB_PASSWORD` directly from the environment. Unlike the main
application, the tests do not fall back to `POSTGRES_PASSWORD`; the test Compose
file also uses the fixed password `test_only`. JBang does not automatically load
`docker/.env`.

**Use only a disposable test database: these tests delete all rows in its four
core_ingest tables.** Six staging tests cover field mapping, reruns, date isolation,
rollback after a JDBC insert group, missing/empty files, duplicate retention, and
maximum long amounts. Existing reader tests remain in ReaderTests.java.

Six additional operational-schema tests cover initial balances and rollback, audit timestamps, relationships and references, end-dated accounts, immutable transactions, and batch retries. They run through the same IngestionTests.java launcher against the disposable test database.
