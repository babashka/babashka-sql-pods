# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Babashka pods for SQL database interaction. A single Clojure codebase compiles into separate native binaries for each supported database (PostgreSQL, HSQLDB, MySQL, Oracle, MSSQL) using GraalVM native-image and compile-time feature flags.

## Build Commands

All builds require `POD_DB_TYPE` env var set to one of: `hsqldb`, `postgresql`, `mysql`, `oracle`, `mssql`.

**Build native binary:**
```bash
export POD_DB_TYPE=postgresql
export GRAALVM_HOME=/path/to/graalvm
bb script/compile.clj
```

**Run tests for a specific database:**
```bash
POD_DB_TYPE=hsqldb clojure -M:test -n pod.babashka.hsqldb-test
POD_DB_TYPE=postgresql clojure -M:test:postgresql -n pod.babashka.postgresql-test
POD_DB_TYPE=mysql clojure -M:test:mysql -n pod.babashka.mysql-test
POD_DB_TYPE=oracle clojure -M:test:oracle -n pod.babashka.oracle-test
POD_DB_TYPE=mssql clojure -M:test:mssql -n pod.babashka.mssql-test
```

Or use the test script: `POD_DB_TYPE=hsqldb script/test`

HSQLDB is the simplest to test locally (in-memory, no extra alias needed). PostgreSQL and MySQL use embedded test databases (zonky, mariaDB4j).

## Architecture

### Feature-flag compilation

`src/pod/babashka/sql/features.clj` defines boolean flags based on the `POD_DB_TYPE` env var. These are used as compile-time feature flags via macros throughout `sql.clj` to conditionally include database-specific code (e.g., JSON handling for PostgreSQL, specific JDBC driver initialization).

### Pod protocol

`src/pod/babashka/sql/sql.clj` is the main entry point (382 lines). It implements the babashka pod protocol:
- **Encoding:** bencode for message framing, transit+json for data serialization
- **Operations:** `describe` (capability map), `invoke` (execute function), `shutdown`
- **Connection management:** In-memory pool using atoms, connections identified by UUID

### Exposed namespaces

Each compiled pod exposes three namespaces (where `<db>` matches the database type):
- `pod.babashka.<db>` — `execute!`, `execute-one!`, `get-connection`, `close-connection`, `with-transaction`
- `pod.babashka.<db>.sql` — `insert-multi!`
- `pod.babashka.<db>.transaction` — `begin`, `commit`, `rollback`

### Build tooling

- **Leiningen** (`project.clj`): dependency management, uberjar builds with database-specific profiles (`+feature/postgresql`, etc.)
- **Clojure CLI** (`deps.edn`): test running with database-specific aliases
- **Babashka** (`bb.edn`): task automation (uberjar, native-image, release)
- **GraalVM reflection configs:** `reflection-<db>.json` files at project root

### Adding a new database

See GitHub issues #15 and #20 for the pattern: add a feature flag in `features.clj`, add conditional branches in `sql.clj`, add a Leiningen profile and deps.edn alias, create a reflection config, and add a test file.
