# Corebank Investigation — Design

Status: PostgreSQL Compose configuration, a Java 21 / Spring Boot 3.4.4 application launched with JBang, YAML configuration, and initial banking records are implemented. Spring Batch readers for all four input record types and a Unicode-aware tokenizer are implemented and tested. PostgreSQL staging tables and explicit command-line ingestion are implemented. Operational tables are implemented through Liquibase. Batch business validation and posting are implemented; trigger watching and output generation remain future work. Updated 2026-10-09.

## Purpose and scope

Create a development and learning project, eventually in a new repository under `~/code`, with:

- A small Java banking core driven by fixed-width batch files.
- A database supporting staging, operational data, and batch output snapshots.
- Redpanda and Redpanda Console.
- Apache Camel routes developed using JBang and Camel MCP tooling.
- A later opportunity to learn Kubernetes with kind and experiment with Apache Pulsar.

The user authorized creating `~/code/corebank-investigation`. The repository contains the agreed scaffold and design document. PostgreSQL Compose configuration is now included. The core now starts Spring Boot and binds directory settings from application.yaml to a Config record.

## Planned repository layout

```text
corebank-investigation/
  corebank/       # Java banking core
  camel/          # Future Camel routes and integration configuration
  postgres/       # Database schema definitions and migrations
  docker/         # Docker Compose and container configuration
  data/
    input/        # Host-mounted core inputs and trigger files
    output/       # Host-mounted balance outputs
    error/        # Host-mounted error artifacts
  docs/           # Design and file specifications
```

This layout has been created as an initial scaffold. Docker Compose is the agreed initial runtime. PostgreSQL is the planned database. Camel and topic implementation details are deferred for now; the earlier integration design below remains future context.

## Confirmed data conventions

| Item | Convention |
| --- | --- |
| IDs | Positive Java `long` (1 through Long.MAX_VALUE); database `BIGINT`; 19 file positions |
| Money | Integer cents; Java `long` and database `BIGINT`; transaction amount field width 20 |
| Example amount | `12345` represents 123.45 currency units |
| Core input and output | Fixed-width files |
| Field padding | Spaces |
| Encoding | UTF-8 |
| Line endings | LF (`\n`) |
| Type field widths | 8 for account type; 9 for relationship type; 6 for transaction type |
| Customer text widths | 10 each for firstname, lastname, city, country; 20 for addressLine1; 2 for province; 7 for postalCode |
| Date field representation | ISO 8601 calendar date: `YYYY-MM-DD` |
| Filename date representation | `YYYYMMDD` |
| Internal date types | Java `LocalDate` and database `DATE` are the proposed implementation |

ID fields use 19 positions and prohibit zero and negative values. Transaction amounts occupy 20 positions. Balance output width and numeric alignment remain undecided. The transaction reader uses the proposed nonnegative amount convention as its working rule. Field widths count Unicode code points after NFC normalization, not UTF-8 bytes. Reject overlength values rather than truncating them. Transaction type uses the codes `CR` (credit) and `DR` (debit), space-padded to the agreed six-character field width. Left-aligned text/dates and right-aligned numbers were suggested but not explicitly confirmed. The account reader represents an absent endDate with ten spaces.

## Confirmed Unicode and text file contract

- Encode files as UTF-8 without a byte-order mark (BOM).
- Use LF (`\n`) line endings.
- Normalize text to Unicode NFC before measuring fields or writing records.
- Measure and parse fixed-width fields by Unicode code points, not bytes or Java UTF-16 code units.
- Pad fields with ordinary U+0020 spaces.
- Reject values exceeding their field width; never silently truncate them.
- Reject embedded newlines, tabs, and other control characters in field values.
- Apply the same contract in the core and future Camel file generation/parsing.

For example, NFC-normalized `José` occupies four code points but five UTF-8 bytes. Record byte lengths may therefore vary even though field positions are fixed in code points. Code points are not necessarily visual characters for every writing system. Java implementations must use code-point-aware counting and slicing rather than assuming `String.length()` counts code points.

The ten-position first name, last name, city, and country fields remain deliberately short. addressLine1 allows 20 positions. Values exceeding their respective field widths produce validation errors. A future legacy interface requiring fixed byte offsets would need a separate, explicit contract change.

## Banking records

| Record | Fields | Allowed values / notes |
| --- | --- | --- |
| Account | `accountId`, `startDate`, `endDate`, `accountType` | `endDate` optional; AccountType values `SAVINGS`, `CHECKING` supersede A1/A2/A3 and are written in an 8-position field |
| Customer | `customerId`, `firstName`, `lastName`, `addressLine1`, `city`, `province`, `postalCode`, `country` | All address fields are required and non-null; postalCode width 7; ID width 19 |
| Relationship | `accountId`, `customerId`, `type` | RelationshipType values `PRIMARY`, `SECONDARY` replace R1/R2/R3 and are written in a 9-position field |
| Transaction | `transactionId`, `accountId`, `type`, `amount` | Types `CR` (credit), `DR` (debit); amount in cents, width 20; reader accepts 0 through Long.MAX_VALUE |

`accountid`, `customerid`, and `transactionid` use the ID convention above. The relationship key has not been decided.

### Implementation and contract checks

- Records are nested in BankingRecords in the corebank package. corebank/src/Core.java is the default-package JBang launcher; CoreApplication.java and Config.java reside in corebank/src/corebank. Production sources are under corebank/src; test sources remain under corebank/tests/corebank.
- Config uses Spring @ConfigurationProperties with the corebank prefix. JBang includes corebank/application.yaml through //FILES application.yaml=../application.yaml; defaults use data/input, data/output, and data/error relative to the launch directory.
- Customer addressLine1, city, province, postalCode, and country are required and must not be null. The convenience constructor that omitted these fields has been removed. The Customer compact constructor now enforces these five non-null requirements with Objects.requireNonNull. Whether blank or whitespace-only strings are rejected must also be made explicit in validation rules.
- Account and relationship files use the full enum names, space-padded to 8 and 9 positions respectively. These widths supersede the original five-position fields. Customer postalCode occupies 7 positions. Widths count NFC-normalized Unicode code points; overlength values must be rejected, never truncated.
- Keep this specification synchronized with explicit user decisions. Record observed implementation changes separately from unresolved contract decisions, and flag contradictions rather than silently relaxing validation.

## Input file readers — implemented

- Spring Batch 5.2.2 (managed by the existing Spring Boot 3.4.4 BOM) supplies FlatFileItemReader. The readers use spring-batch-infrastructure; the Batch starter, job repository, and database-backed jobs are deferred.
- CodePointLineTokenizer normalizes NFC before checking exact record length and slicing by Unicode code point. It rejects control characters, BOMs, Unicode line/paragraph separators, and invalid surrogates.
- CustomerFileReader.create(path) returns a new reader. The caller opens it with an ExecutionContext, reads until null, and closes it in a finally block. This reader is not yet wired into the startup runner or a batch job.
- Customer widths in order: ID 19, firstName 10, lastName 10, addressLine1 20, city 10, province 2, postalCode 7, country 10. Total customer record width is 88 positions. The reader and tests now use the production width exclusively.
- Files use strict UTF-8 decoding and LF separators. CRLF, BOMs, malformed UTF-8, blank records, and short/long records fail. Empty files are accepted; missing files fail. A final record without a trailing LF is accepted by the current reader.
- Field padding is stripped when mapping to Customer. Alignment is not enforced pending the contract decision. All-space address fields currently become empty strings, not null; blank-address rejection remains undecided. Shared FileReaderSupport validates unsigned decimal digits and a value from 1 through Long.MAX_VALUE. Zero, negative IDs, explicit plus signs, and overflow are rejected by all four readers. Record constructors do not yet enforce this invariant for directly constructed objects.
- Parsing failures include the resource and line number through Spring Batch. No skip policy is configured. Duplicate IDs and database reference checks remain for staging validation.
- JUnit Jupiter 5.11.4 tests run through the matching JUnit Platform console standalone 1.11.4, explicitly pinned because the standalone artifact is not version-managed by the Boot BOM. Run from the repository root: `jbang corebank/tests/ReaderTests.java`.
- Sixty-two tests cover all four input record types, both account types, optional end dates, strict calendar dates, production-width IDs including Long.MAX_VALUE, zero/negative IDs and overflow, Unicode normalization and boundaries, invalid records, UTF-8, and diagnostics.
- AccountFileReader.create(path) uses fields at positions 1–19 (accountId), 20–29 (startDate), 30–39 (endDate), and 40–47 (accountType). startDate is required; endDate is either ten spaces or YYYY-MM-DD. Date ordering and account lifecycle rules remain for business validation. Both readers share strict UTF-8/LF handling through FileReaderSupport.

### Relationship and transaction layouts

| Record | Field | Positions (inclusive) | Width |
| --- | --- | --- | --- |
| Relationship | accountId | 1–19 | 19 |
| Relationship | customerId | 20–38 | 19 |
| Relationship | type | 39–47 | 9 |
| Transaction | transactionId | 1–19 | 19 |
| Transaction | accountId | 20–38 | 19 |
| Transaction | type | 39–44 | 6 |
| Transaction | amount | 45–64 | 20 |

RelationshipFileReader.create(path) and TransactionFileReader.create(path) share the same UTF-8/NFC/LF handling and positive-ID validation as the existing readers. Relationship types are PRIMARY/SECONDARY; transaction types are CR/DR. Exact record widths are 47 and 64 code points respectively.

The user selected a 20-position transaction amount field. This does not expand the Java long numeric range: values above Long.MAX_VALUE are rejected. Working implementation follows the proposed nonnegative-cent convention, accepting zero, rejecting signs and decimal fractions; CR/DR determines the eventual posting direction. No posting arithmetic is implemented yet. Confirm the amount-sign convention before database posting. Direct record constructors still permit values that the file readers reject.

Readers validate individual records only. Cross-record references, duplicate detection, relationship uniqueness, staging, and posting remain future work.

## Database boundaries

PostgreSQL is the planned database.

| Schema | Responsibility |
| --- | --- |
| `camel_ingest` | Continuously stage topic records enriched with an assigned batch date |
| `core_ingest` | Stage and validate records read from the core's input files |
| `core` | Hold operational customers, accounts, relationships, transactions, and balances |
| `core_output` | Hold balance snapshots with a batch date, used to generate output files |

Proposed table names in `camel_ingest` and `core_ingest`: `customer`, `account`, `relationship`, `transaction`. Use a `batch_date` column in shared Camel staging tables rather than separate tables for each date.

Confirmed: `core` must contain a balance table. Proposed minimum fields are `accountid` and `balance` in cents. `core_output.balance` must include the batch date; proposed minimum fields are `batchDate`, `accountid`, and `balance`.

## Core file interface

For business date `2026-10-09`, input files are:

```text
customer_20261009.dat
account_20261009.dat
relationship_20261009.dat
transaction_20261009.dat
batch_20261009.trg
```

Arrival of `batch_YYYYMMDD.trg` in `input/` triggers the core batch for that date. Empty trigger contents were suggested; the filename supplies the date.

The core:

1. Reads the matching data files into `core_ingest`.
2. Validates the staged records.
3. Merges customers, accounts, and relationships into existing `core` data, creating or updating records.
4. Processes transactions and maintains `core.balance` with each new account starting at zero. Balances may be positive or negative; daily credits and debits change the balance.
5. Writes a dated balance snapshot into `core_output`.
6. Generates `balance_YYYYMMDD.dat` from that snapshot.

The only currently requested business output is the account balance file. It contains exactly `accountid` and `balance` (in cents), and only changed accounts rather than a full account snapshot. Field widths remain undecided. Clarify whether “changed” includes accounts with transactions that net to zero, or newly created zero-balance accounts.

Customer, account, and relationship inputs are full-record updates; partial updates are not supported. Input files must have at most one record per business key per batch date. Future Camel staging must therefore upsert by batch date and business key before extraction. Distinct transactions remain distinct by `transactionid`, not by account ID. Relationship key definition remains open.

Currency scope is CAD/USD, using integer cents. No currency field or foreign-exchange behaviour has been specified; decide whether a run uses one configured currency or accounts carry a currency before supporting both simultaneously. Proposed posting convention is balance plus credits minus debits, using nonnegative transaction amounts; the amount-sign convention remains to be confirmed.

The core has no direct dependency on Redpanda or Camel. It communicates through files and its database schemas.

## Host directories and mounts

Confirmed: `input/`, `output/`, and `error/` must exist on the host machine and all three must be mounted into the core container.

| Host directory | Proposed core container path | Purpose |
| --- | --- | --- |
| `data/input/` | `/data/input` | Batch data and trigger files |
| `data/output/` | `/data/output` | Balance output files |
| `data/error/` | `/data/error` | Error artifacts; exact contents and handling to be defined |

The host directories live under the repository’s `data/` directory. Containerised Camel needs access to the input/output directories it uses. Camel running on the host through JBang can use the host paths directly.

## Camel ingestion and business-date clock

Source topics contain real-time records. A separate control topic carries dates. The conversation called this both the “batch” topic and the “date” topic; its final name is undecided.

Confirmed assignment rule:

**Assigned batch date = latest received control-topic date + one calendar day.**

This is driven by the control message, not by the host's current date.

| Event | Intended result |
| --- | --- |
| Control topic receives `2026-10-08` | Active assignment date becomes `2026-10-09` |
| Data messages arrive | Camel stages them in `camel_ingest` with `batch_date = 2026-10-09` |
| Control topic receives `2026-10-09` | Active assignment date becomes `2026-10-10`; the preceding batch can be closed |
| Further data messages arrive | Camel stages them with `batch_date = 2026-10-10` |

Camel continuously writes to database tables. It does not continuously append directly to the final core input files. Once a day, a separate extraction step selects a closed batch date and generates the four fixed-width input files.

Recommended interpretation, accepted as the working design: assign the batch date when Camel stages the record. A delayed record staged after the date transition belongs to the new batch, even if it was produced earlier. Different topics do not provide one global arrival order; the implementation must define and coordinate the staging/date-transition boundary explicitly.

Recommended recovery behaviour:

- Persist the active business date across restarts.
- Wait for an initial control date before assigning data records.
- Treat a repeated control date as a no-op.
- Preserve each record's original batch assignment when retrying.
- Retain source topic, partition, and offset as ingestion identity and traceability metadata.

Backward dates, skipped dates, control-message replay, startup ordering, and concurrent consumers still need explicit policies.

## Daily extraction and return flow

```text
Real-time data topics + date control topic
                    |
                    v
         Camel date enrichment and staging
                    |
                    v
          camel_ingest (batch_date)
                    |
          Daily closed-date extraction
                    |
                    v
 Host input/: four .dat files, then batch_YYYYMMDD.trg
                    |
                    v
              Java banking core
       core_ingest -> core -> core_output
                    |
                    v
       Host output/: balance_YYYYMMDD.dat
                    |
                    v
          Camel reads and publishes
                    |
                    v
              Balance output topic
```

The precise extraction trigger is unresolved: it may run immediately after a date transition or at a daily scheduled time after closure. It must not extract the currently open batch date.

Suggested file publication protocol:

1. Generate files under temporary names.
2. Finish and publish all four input data files.
3. Create the batch trigger last.
4. Generate the balance output under a temporary name and publish its final name only when complete.

Camel's output route then reads the completed balance file and publishes to a topic. Message schema, topic name, and whether publication is per account or per file remain undecided.

## Proposed reliability defaults — not all confirmed

- Commit core changes and the output snapshot in one database transaction.
- Regenerate a failed output file from `core_output` without reposting transactions.
- Track batches and source line numbers for diagnostics and reruns.
- Prevent duplicate transaction posting and duplicate staging on message redelivery.
- Use a stable identity such as batch date plus account ID for published balances; consumers must handle redelivery.
- Require all four data files, allowing empty files where there are no records.
- Reject invalid batches before updating operational data.
- Treat omitted customer/account records as unchanged rather than deleted.

Database commits, filesystem publication, and broker acknowledgements are separate operations. Recovery must be designed explicitly; no end-to-end exactly-once guarantee has been established.

## Development and infrastructure direction

Docker Compose is confirmed for the initial implementation. Later stages remain proposed:

1. Docker Compose for the database, Java core, Redpanda, and Console.
2. Camel routes run locally through JBang, using Camel MCP for development assistance.
3. Move the same application to a single-node kind cluster to learn Kubernetes and `kubectl`.
4. Add an Apache Pulsar integration experiment while preserving the core file interface.

Camel MCP is development tooling; it is not required in the runtime banking data path. Swarm was discussed but is not the recommended intermediate step because the learning goal is Kubernetes. Neither Camel nor Pulsar requires Kubernetes.

For kind, shared file access and persistent database/broker storage need explicit design. Compose host mounts do not automatically become Kubernetes storage configuration.

## Open decisions

- Core currently uses Java 21, Spring Boot 3.4.4, and JBang. Future version upgrades remain separate decisions; JUnit console tests run through ReaderTests.java.
- Balance output width. Transaction amount width is 20; ID width is 19, with zero and negative IDs prohibited.
- Field alignment and blank/whitespace-only customer address validation. Optional endDate uses ten spaces in the implemented account reader. Null customer address fields are prohibited.
- Transaction amount-sign rules and confirmation of credit/debit arithmetic.
- Currency configuration or per-account currency; no FX requirement currently defined.
- Exact changed-output rule for net-zero activity and newly created zero-balance accounts.
- Relationship uniqueness, multiple types per account/customer pair, and deletion semantics.
- Duplicate-key rejection behaviour, invalid records, missing files, rejected batches, and error-directory contents.
- Batch identity, same-date corrections/reruns, retention, and file archival.

Deferred Camel decisions: topic names and message formats, upsert ordering, date-transition coordination, daily extraction scheduling, and output publication/recovery. These do not need to be resolved for the initial core design.

## Reference documentation

- [Docker Compose](https://docs.docker.com/compose/)
- [Redpanda and Console single-broker Compose example](https://docs.redpanda.com/labs/docker-compose/single-broker/)
- [kind quick start](https://kind.sigs.k8s.io/docs/user/quick-start/)
- [Camel CLI / JBang](https://camel.apache.org/manual/camel-jbang.html)
- [Camel MCP server](https://camel.apache.org/manual/camel-jbang-mcp.html)
- [Camel Kafka component](https://camel.apache.org/components/4.18.x/kafka-component.html)
- [Camel Pulsar component](https://camel.apache.org/components/4.14.x/pulsar-component.html)

These links supported the design discussion; select and verify compatible versions when implementation starts.

## Staging ingestion — implemented 2026-10-09

`--ingest=YYYY-MM-DD` explicitly loads all four dated inputs through the existing
readers into core_ingest using JDBC and the PostgreSQL driver managed by the Boot BOM.
Liquibase applies postgres/changelog/db.changelog-master.xml on startup before ingestion. Database connection
settings live under corebank.database with COREBANK_DB_URL, COREBANK_DB_USER,
and COREBANK_DB_PASSWORD environment overrides.

Working staging policy: require all four files, accept empty files, reject parsing
errors, and atomically replace all rows for the requested date. Preserve prior rows
on failure and serialize same-date loads through a transaction advisory lock.
Rows retain source filename and line; business-key duplicates and unresolved
references are preserved for the future validator. Staging identity is date plus
source line within each record-type table, so relationship business-key policy
remains open. Inserts are grouped in 500-row JDBC batches, with a single commit
for all four files. Files must remain unchanged during the load.

This is an explicit staging command, not yet a Spring Batch job or trigger watcher.
It does not mark batches processed, post balances, or generate outputs. Same-date
correction rules after operational posting remain unresolved. Six integration tests
against PostgreSQL supplement the existing 62 reader tests.

## Liquibase schema lifecycle — implemented 2026-10-09

Spring Boot initializes Liquibase before command-line runners. The existing Boot BOM
supplies Liquibase 4.29.2; JBang bundles the master XML and referenced SQL resources.
The Docker initialization mount and manual migration workflow are replaced by this
single changelog. Initial changesets create schemas and staging tables, adopting the
known existing objects using IF NOT EXISTS. History, checksums, and locks live in
public.databasechangelog and public.databasechangeloglock and persist with the volume.
New migrations are appended as new changesets; applied scripts must not be edited.
Running the core without --ingest now connects to the database, migrates, and exits.
Migration failure prevents ingestion. Liquibase handles migration transactions;
BatchIngestor continues to explicitly manage its separate JDBC ingestion transaction.

## Operational schema — implemented 2026-10-09

This section supersedes earlier open relationship-key and end-date decisions.
Liquibase changeset 003 creates core.customer, core.account, core.relationship,
core.transaction, core.balance, and core.batch_run. Types and field limits match
staging. All tables have created_at and updated_at TIMESTAMPTZ processing timestamps;
business dates remain DATE values. Update triggers preserve created_at and refresh
updated_at. Immutable transactions retain their insertion timestamps.

Relationships are keyed by (account_id, customer_id), with one PRIMARY or SECONDARY
type per pair. Multiple primary customers per account are allowed; no exclusivity
rule has been requested. Foreign keys do not cascade deletes. Account end dates
must be on or after start dates.

Any non-null account end_date prohibits new transactions, even when the end date
is in the future or the supplied batch date precedes it. An insert trigger enforces
this while holding a row lock that conflicts with concurrent account closure.
Closing an account does not invalidate its historical transactions.

Account insertion automatically creates a zero balance in the same transaction.
A database trigger implements this because there is no account-creation service yet.
The future application must not also insert the initial balance. Balances are signed
BIGINT cents. Transactions use nonnegative BIGINT amounts and CR/DR direction.
Transaction IDs are globally unique; updates/deletes are rejected. Corrections must
use a new reversing transaction. A replay must compare existing contents before
being treated as a no-op; conflict handling belongs to the future posting service.

Each batch_run row represents an attempt with RUNNING, COMPLETED, or FAILED status,
start/finish timestamps, four nonnegative record counts, and optional failure text.
Counts describe successfully applied records, not raw staging rows. Failed attempts
can be retried; only one RUNNING or COMPLETED attempt is allowed per business date.
Transactions reference both the run ID and its matching business date. Future posting
must create the attempt first, commit operational changes and successful completion
together, and record failure after rolling back operational changes. Crash recovery
and correction of completed dates remain future workflow decisions.

This migration does not post staged records, update balances on transaction insert,
or populate batch runs. Those actions require an atomic application posting flow.
core_output.balance snapshots remain future work; their output format is still open.

## Development data generator — implemented 2026-10-09

Generate.java provides a separate JBang/picocli command. It reads operational
reference data using a read-only repeatable-read transaction, then creates selected
record types and empty files for other types. Per-type defaults are 10 when selected;
--all selects every type and explicit counts override its defaults. See README for
usage and limitations. Transactions exclude every end-dated account and accounts
starting after the business date. Generated accounts start on the business date.
All output follows the NFC/code-point/UTF-8/LF contract; text/dates are left-aligned
with spaces on the right, and numbers are right-aligned.
Generation validates records through integration tests with existing readers.
It emits no trigger and performs no migrations or database writes.

## Core processing — implemented 2026-10-09

This section supersedes earlier statements that validation/posting are unimplemented.
BatchProcessor, invoked with --process=YYYY-MM-DD, validates staged business keys
and references, upserts customers/accounts/relationships, inserts new transactions,
and updates balances using CR as addition and DR as subtraction. Transactions are
immutable: existing IDs with identical account/type/amount are no-ops regardless of
incoming batch date; differing contents fail. Duplicate keys within staging fail.
Reference omissions do not delete operational rows. New transaction accounts must
have no end date and must start on or before the batch date. Negative balances and
zero amounts are allowed. Numeric aggregation checks the final signed BIGINT balance
without overflowing intermediate totals. The account trigger initializes balances.

A committed RUNNING attempt precedes the operational transaction. All upserts,
transaction inserts, balances, applied counts, and COMPLETED status commit together.
Errors roll back and mark the attempt FAILED separately. Failed counts stay zero.
Completed-date retries return original counts without posting; completed dates
cannot be re-ingested. Failed dates may be corrected/re-ingested/retried. Unprocessed
dates older than the latest completed date are rejected to prevent stale upserts.
A stale RUNNING attempt blocks new processing until operator investigation/recovery.
Automatic crash recovery and completed-date correction remain deferred.

A global session advisory lock serializes processors. A session date lock shares
the ingestor's transaction-lock namespace and persists across attempt commits,
preventing concurrent staging replacement. These locks require cooperating writers.
Migration 004 records successful ingestion atomically, including all-empty batches.
Pre-migration staging is preserved but must be re-ingested to receive a receipt.
ProcessingTests.java runs 13 PostgreSQL integration tests against a disposable DB.
Output snapshots/files and trigger watching remain separate future steps.
