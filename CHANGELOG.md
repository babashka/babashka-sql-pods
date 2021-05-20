# Changelog

For a list of breaking changes, check [here](#breaking-changes)

## v0.0.7 (requires bb 0.4.3+)

Breaking changes:

- Arrays are now always returned as vectors, the support for
  `pod.babashka.sql/read {:array :array}` option has been dropped.

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
  `pod.babashka.sql/read {:array :array}` option has been dropped.

- Instead of writing `json` or `jsonb` via metadata, this must now be done using the `write-json` and `write-jsonb` functions from the main db namespace.
