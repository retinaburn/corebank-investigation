# Corebank Investigation

A Java banking batch and Apache Camel integration learning project.

## Status

Repository scaffold with a local PostgreSQL Compose configuration. Banking application implementation has not started.

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
