# Corebank Investigation — Design

Status: repository scaffold created with user authorization. PostgreSQL Compose configuration has been added; banking application implementation has not started. This document captures the conversation as of 2026-10-09.

## Purpose and scope

Create a development and learning project, eventually in a new repository under `~/code`, with:

- A small Java banking core driven by fixed-width batch files.
- A database supporting staging, operational data, and batch output snapshots.
- Redpanda and Redpanda Console.
- Apache Camel routes developed using JBang and Camel MCP tooling.
- A later opportunity to learn Kubernetes with kind and experiment with Apache Pulsar.

The user authorized creating `~/code/corebank-investigation`. The repository contains the agreed scaffold and design document. PostgreSQL Compose configuration is now included. Application implementation remains future work.

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
| IDs | Java `long`; database `BIGINT` |
| Money | Integer cents; Java `long` and database `BIGINT` |
| Example amount | `12345` represents 123.45 currency units |
| Core input and output | Fixed-width files |
| Field padding | Spaces |
| Encoding | UTF-8 |
| Line endings | LF (`\n`) |
| Type field widths | 5 for account and relationship; 6 for transaction |
| Customer text widths | 10 each for firstname, lastname, addressLine1, city, country; 2 for province |
| Date field representation | ISO 8601 calendar date: `YYYY-MM-DD` |
| Filename date representation | `YYYYMMDD` |
| Internal date types | Java `LocalDate` and database `DATE` are the proposed implementation |

ID and money widths, numeric alignment, and signed amount representation remain undefined. Field widths count Unicode code points after NFC normalization, not UTF-8 bytes. Reject overlength values rather than truncating them. Transaction type uses the codes `CR` (credit) and `DR` (debit), space-padded to the agreed six-character field width. Left-aligned text/dates and right-aligned numbers were suggested but not explicitly confirmed. An all-space optional date was also suggested.

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

The agreed ten-position customer text fields remain deliberately short; longer names or addresses produce validation errors. A future legacy interface requiring fixed byte offsets would need a separate, explicit contract change.

## Banking records

| Record | Fields | Allowed values / notes |
| --- | --- | --- |
| Account | `accountid`, `startDate`, `endDate`, `type` | `endDate` optional; types `A1`, `A2`, `A3` |
| Customer | `customerid`, `firstname`, `lastname`, `addressLine1`, `city`, `province`, `country` | Customer text widths as above; ID width still open |
| Relationship | `accountid`, `customerid`, `type` | Types `R1`, `R2`, `R3` |
| Transaction | `transactionid`, `accountid`, `type`, `amount` | Types `CR` (credit), `DR` (debit); amount in cents |

`accountid`, `customerid`, and `transactionid` use the ID convention above. The relationship key has not been decided.

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

- Final repository name confirmation; Java version/framework/build tool and dependency versions.
- ID and money widths.
- Field alignment and null representation.
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
