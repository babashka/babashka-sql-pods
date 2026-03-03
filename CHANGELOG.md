# Changelog

For a list of breaking changes, check [here](#breaking-changes)

[babashka-sql-pods](https://github.com/babashka/babashka-sql-pods): Babashka pods for SQL databases

## v0.1.5

- [#72](https://github.com/babashka/babashka-sql-pods/issues/72): Handle concurrent requests ([@katangafor](https://github.com/katangafor))
- Upgrade to Oracle GraalVM 25.0.2
- Upgrade `next.jdbc` to 1.3.1093
- Upgrade `cheshire` to 6.1.0 (Jackson 2.12 -> 2.20)
- Upgrade PostgreSQL JDBC driver to 42.7.10
- Upgrade MSSQL JDBC driver to 13.2.1
- Upgrade HSQLDB to 2.7.4
- Upgrade MySQL Connector/J to 9.6.0
- Remove DuckDB support
- Upgrade `transit-clj` to 1.0.333
- Upgrade Clojure to 1.12.4
- Upgrade `nrepl/bencode` to 1.2.0
- Upgrade test deps: `mariaDB4j` to 3.3.0, `embedded-postgres` to 2.2.0
- Upgrade Oracle JDBC driver (`io.helidon.integrations.db/ojdbc`) to 4.3.4
- [#51](https://github.com/babashka/babashka-sql-pods/issues/51): macOS binaries are now aarch64 (Apple Silicon). Intel (x64) macOS binaries are no longer provided.

## v0.1.4

- [#68](https://github.com/babashka/babashka-sql-pods/issues/68): Upgrade to Oracle GraalVM 23

## v0.1.3

- Add support for DuckDB ([@avelino](https://github.com/avelino))

## v0.1.2

- Upgrade libraries
- Fix bug with mssql on Windows ([@kbosompem](https://github.com/kbosompem))

## v0.1.1

- Remove dynamic linux builds

## v0.1.0

- Fix bug in `with-transaction` for MySQL [#47](https://github.com/babashka/babashka-sql-pods/issues/47)
- Upgrade `next.jdbc` to `1.2.753`

## v0.0.8

- Releases for Windows
- Releases for Oracle

## v0.0.7 (requires bb 0.4.3+)

Breaking changes:

- Arrays are now always returned as vectors, the support for
  `:pod.babashka.sql/read {:array :array}` option has been dropped.
- Writing arrays must always be done using an explicit array instead of using the `:pod.babashka.sql/write` option
- Instead of writing `json` or `jsonb` via metadata, this must now be done using the `write-json` and `write-jsonb` functions from the main db namespace.

## v0.0.6

All linux binaries are now statically linked with musl.

## v0.0.5

- Fix insertion and retrieving of byte arrays

## v0.0.4

- Automatically convert json and jsonb into Clojure values (for PostgreSQL only)
- Add `:pod.babashka.sql/write` and `:pod.babashka.sql/read` options to give
  precise control over how arrays and json/jsonb values are read and
  written. See [arrays](https://github.com/babashka/babashka-sql-pods#arrays)
  and [json](https://github.com/babashka/babashka-sql-pods#json) docs.

## v0.0.3

- Add `insert-multi!`
- Support MS SQL ([@jvtrigueros](https://github.com/jvtrigueros))
- Support inserting Java arrays [#7](https://github.com/babashka/babashka-sql-pods/issues/7)
- Support querying rows with array results (behavior: automatic conversion to Clojure vectors)
- Switch to transit as payload format [#27](https://github.com/babashka/babashka-sql-pods/issues/27)

## v0.0.2

- Add `execute-one!`
- Support Oracle via Helidon driver ([@holyjak](https://github.com/holyjak))
- Add static builds for linux ([eccentric-j](https://github.com/eccentric-j))

## v0.0.1

Initial release.

## Breaking changes

### 0.0.7

- Arrays are now always returned as vectors, the support for
  `:pod.babashka.sql/read {:array :array}` option has been dropped.
- Writing arrays must always be done using an explicit array instead of using the `:pod.babashka.sql/write` option
- Instead of writing `json` or `jsonb` via metadata, this must now be done using the `write-json` and `write-jsonb` functions from the main db namespace.
