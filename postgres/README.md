# Local PostgreSQL

Uses the [official PostgreSQL image](https://hub.docker.com/_/postgres), pinned to major version 18 and the Bookworm image variant. Minor updates can be pulled within this major version; it is not an immutable digest pin. No custom Dockerfile is needed.

## Start

From the repository root:

```sh
cp docker/.env.example docker/.env
# Edit docker/.env and replace POSTGRES_PASSWORD.
docker compose --env-file docker/.env -f docker/compose.yaml up -d --wait postgres
```

Host connection: `localhost:5432`, database `corebank`, user `postgres`, with the password in `docker/.env`. Change `POSTGRES_PORT` if port 5432 is occupied. Future Compose services use `postgres:5432`.

This initial local setup uses the bootstrap administrator. Add separate restricted application roles when implementing core and Camel. The environment file is ignored by Git. Network access is bound to localhost.

## Inspect and stop

```sh
docker compose --env-file docker/.env -f docker/compose.yaml ps
docker compose --env-file docker/.env -f docker/compose.yaml logs postgres
docker compose --env-file docker/.env -f docker/compose.yaml exec postgres psql -U postgres -d corebank
docker compose --env-file docker/.env -f docker/compose.yaml down
```

Inside psql, use `\dn` to list schemas and `\q` to exit.

## Storage and initialization

A Docker named volume preserves the database through container replacement and normal `down`. Do not add `--volumes` to `down` unless intentionally deleting the database. Database storage is separate from `data/input`, `data/output`, and `data/error`.

PostgreSQL 18 stores its cluster beneath `/var/lib/postgresql/18/docker`; the volume is mounted at `/var/lib/postgresql` as required by the official image.

On the first start with an empty volume, `init/001-schemas.sql` creates `core_ingest`, `core`, `core_output`, and `camel_ingest`. Initialization files do not run again against existing data. Later schema changes require migrations; editing an initialization file is not a migration. Changing the environment password likewise does not change an existing database password.

UTF-8 database encoding and UTC timezone are configured. The application remains responsible for NFC normalization, field widths, and the topic-controlled business date. The readiness check tests whether PostgreSQL accepts connections; it does not validate application schemas or credentials.

Upgrade the PostgreSQL major version only with an explicit database migration plan.
