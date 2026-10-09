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
