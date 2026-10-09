# Corebank Investigation

A Java banking batch and Apache Camel integration learning project.

## Status

Java 21 / Spring Boot core launched with JBang, YAML configuration, banking records, and tested Spring Batch readers for all four input files. PostgreSQL staging ingestion is implemented for all four files. Operational tables, audit timestamps, account balance initialization, and batch-attempt tracking are defined through Liquibase. Business validation, reference-data upserts, immutable transaction posting, and atomic balance updates are implemented.

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
Liquibase runs automatically before ingestion. Running without `--ingest` or `--process` connects
to PostgreSQL, applies pending migrations, and exits without loading files.
See [database migration setup](postgres/README.md#liquibase-migrations).

Each row in core_ingest records batch_date, source_file, and source_line.
All four loads commit together; any parsing or database failure rolls back the load.
A rerun atomically replaces that date's staged rows and retains other dates.
Same-date loads are serialized using a PostgreSQL transaction advisory lock.
JDBC inserts execute in groups of 500 within one transaction.
Duplicate business keys are retained for processing validation, not silently overwritten.
This command only stages data. The separate --process command validates cross-record
references, updates core tables, and posts balances. Trigger-file watching and output
generation remain future steps.

### Process a staged batch

From the repository root, with the database environment configured as above:

```sh
jbang corebank/src/Core.java --ingest=2026-10-09
jbang corebank/src/Core.java --process=2026-10-09
```

Run ingestion and processing as separate commands. Processing validates the date's
staging records, upserts customers/accounts/relationships, inserts new immutable
transactions, and adds credits/subtracts debits from balances in integer cents.
New accounts receive their zero balance from the existing database trigger.
Omitted reference records remain unchanged; negative balances are allowed.

All operational changes and successful batch completion commit together. On an
error they roll back, and the attempt is recorded as FAILED with zero applied
counts and a diagnostic. Correct the inputs, re-ingest, and retry a failed date.
Duplicate business keys within a file fail the whole batch. Transaction IDs are
globally unique: identical account/type/amount replays are skipped, even on later
dates; conflicting contents fail. Corrections require a new reversing transaction.
Any end date, or a start date after the batch date, prohibits new transactions.
The final net balance must fit signed BIGINT cents; overflow fails the batch.

A retry of a COMPLETED date returns its stored counts without applying changes.
Re-ingesting a RUNNING or COMPLETED date is rejected. Process dates in ascending
order; an unprocessed date earlier than the latest completed date is rejected.
Counts include applied reference upserts and newly inserted transactions only.
Processing is serialized across application instances and holds the same date
lock as ingestion across attempt creation and posting. These locks coordinate
application commands, not arbitrary manual SQL writers.

Migration 004 adds a receipt written atomically with successful ingestion, including
four empty files. **Re-ingest dates staged before this migration before processing
for the first time.** Existing staging data is retained, but no receipt is inferred.
Do not manually modify staging after ingestion.

A process crash can leave a durable RUNNING attempt. New processing is then blocked
pending operator investigation; automatic crash recovery is not implemented. Verify
the original process/connection has ended and inspect the attempt before marking
an abandoned RUNNING row FAILED with completed_at and an explanatory error_message.
A COMPLETED attempt must never be changed to FAILED. Retry after recovery.
Trigger watching, output snapshots, and balance-file generation remain future work.

### Balance output — agreed, not yet implemented

The next stage saves dated balances in `core_output.balance` and generates
`balance_YYYYMMDD.dat`. Include new accounts even when their balance is zero and
there are no transactions. Include existing accounts only when the batch posts
new transactions, including zero-amount or net-zero activity. Skipped transaction
replays and reference-only updates do not qualify an existing account for output.
Each qualifying account appears once with its final balance in cents.

Records contain a 19-position account ID followed by a 20-position signed balance,
both right-aligned and space-padded: 39 positions plus LF, UTF-8 without a BOM.
Negative balances use a minus sign; zero and positive values have no sign. There
are no headers, delimiters, or decimal points. See the
[balance output contract](docs/banking-core-design.md#balance-output-contract--confirmed-2026-10-09).
Snapshot creation and file generation are pending; there is no output command yet.

### Processing integration tests

With the disposable test database and COREBANK_TEST_DB_* variables below:

```sh
jbang corebank/tests/ProcessingTests.java
```

This launcher migrates the database and runs 13 tests, including concurrent posting,
replays, upserts, invalid batches, failed retries, empty ingestion, and overflow.
**It truncates all operational and staging tables; use only a disposable database.**

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

## Generate sample input files

From the repository root, with the operational migrations applied and
`COREBANK_DB_PASSWORD` (or `POSTGRES_PASSWORD`) exported:

```sh
# Ten records of every type, including references to this batch's new entities.
jbang corebank/src/Generate.java --date=2026-10-09 --all

# Existing eligible accounts only; other three files are empty.
jbang corebank/src/Generate.java --date=2026-10-10 --transactions=100

# Counts are independently selectable; a bare flag means 10.
jbang corebank/src/Generate.java --date=2026-10-11 --customers=20 --accounts=30 --relationships

# Explicit values override --all defaults (including zero).
jbang corebank/src/Generate.java --date=2026-10-12 --all --transactions=0 --seed=123 --output=/tmp/corebank-samples
```

All four `.dat` files are created; unselected types are zero-byte files. At least
one type or `--all` must be selected. Counts must be nonnegative. Existing dated
files and trigger files cause failure rather than replacement. Output defaults to
`corebank.input-directory` from the shared `corebank/application.yaml` (normally
`data/input`). A configuration-only Spring Boot context binds the same Config record
as the core, including external application YAML, profiles, and environment overrides.
It does not scan core components or enable auto-configuration, so no core startup
runner, datasource, or Liquibase migration runs. Picocli handles arguments first;
--help and invalid arguments do not start Spring. Like the core, it does not
automatically load Docker .env files. Database environment conventions remain: COREBANK_DB_URL,
COREBANK_DB_USER (falling back to POSTGRES_USERNAME), and COREBANK_DB_PASSWORD
(falling back to POSTGRES_PASSWORD). Explicit --db-url/--db-user/--output options override the corresponding Spring settings.
Use standard Spring environment settings (such as SPRING_PROFILES_ACTIVE and
SPRING_CONFIG_ADDITIONAL_LOCATION) for profile or external YAML selection.
Run --help for options. Generation does not migrate, insert, or update database data.

The generator reads a consistent read-only PostgreSQL snapshot. Customers/accounts
created within the batch join the reference pools; relationships avoid existing
core account/customer pairs. Transactions use only accounts whose end_date is null
and start_date is on/before the requested date, plus newly generated accounts.
Relationships may refer to any existing account; the end-date prohibition applies
to transactions. Missing references or insufficient unused pairs fail before data
files are written. Customer data uses bounded synthetic examples, including Unicode.
Amounts are 1–100,000 cents, with randomly selected CR/DR types.

Positive random long IDs are checked against both core and core_ingest IDs and
against IDs generated in the current run. The date and seed determine the random
stream: matching date, seed, database snapshot, and generator version reproduce
output. IDs are not reserved in the database, so pending files and concurrent
changes cannot be guaranteed collision-free. Generate/ingest sequentially and
revalidate references at posting. All reference IDs are currently loaded into memory;
this utility targets development datasets, not production-scale databases.

Files are completed in a temporary subdirectory before publication. Publication
of four files is not a single atomic filesystem operation: start ingestion only
after successful completion, and do not run simultaneous generators for the same
output/date. No batch trigger is created automatically. A crash during publication
may leave a subset of completed files that must be reviewed before retrying.

### Generator integration tests

Against a **disposable database only** (these tests truncate operational and staging tables):

```sh
COREBANK_TEST_DB_URL=jdbc:postgresql://localhost:55432/corebank_test \
COREBANK_TEST_DB_PASSWORD=test_only jbang corebank/tests/GeneratorTests.java
```

The launcher applies Liquibase first. Six tests cover self-contained generation
through the real readers/ingestor, existing-account eligibility, empty files,
missing references, relationship exhaustion, reproducibility, overwrite protection,
and Unicode formatting/argument validation.

### Generator name and street lists

Edit `corebank/src/resources/firstnames.txt`, `lastnames.txt`, and `streets.txt`.
Each UTF-8 file contains one value per line. Blank lines and lines beginning with
`#` are ignored. Names allow up to 10 NFC-normalized Unicode code points; street
names allow 15, leaving space for the generated house number in the 20-position
address field. Invalid or empty lists fail generation with a clear error; values
are never truncated. JBang bundles these files as classpath resources for both
the generator and its test launcher. Changes to the lists can change seeded output.
