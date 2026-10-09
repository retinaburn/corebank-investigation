# Corebank Investigation

A Java banking batch and Apache Camel integration learning project.

## Status

Java 21 / Spring Boot core launched with JBang, YAML configuration, banking records, and tested Spring Batch readers for all four input files. PostgreSQL Compose is configured; database-backed batch processing is still to come.

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

From the repository root:

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
