# Babashka sql pods

[Babashka](https://github.com/borkdude/babashka) pods for interacting with SQL
databases.

Supported databases:

- [HSQLDB](http://www.hsqldb.org/)
- [Microsoft SQL Server](https://www.microsoft.com/nl-nl/sql-server)
- [MySQL](https://www.mysql.com)
- [Oracle](https://www.oracle.com/database/)
- [PostgresQL](https://www.postgresql.org/)

PRs for other SQL databases are welcome. (Look at #15 + #20 for an example of adding a new DB.)

## Install

The following installation methods are available:

- Use the latest version from the
  [pod-registry](https://github.com/babashka/pod-registry). This is done by
  calling `load-pod` with a qualified keyword:

  ``` clojure
  (require '[babashka.pods :as pods])
  (pods/load-pod 'org.babashka/postgresql "0.1.0")
  ```

  Babashka will automatically download the pod if it is not available on your system yet.

- Download a binary from Github releases
<!-- - With [brew](https://brew.sh/): `brew install borkdude/brew/pod-babashka-<db>` -->
<!-- where `<db>` must be substited with the database type, either `hsqldb` or -->
<!-- `postgresql`. -->

## Compatibility

Pods from this repo require babashka v0.4.3 or later.

## Available vars

The pods expose these namespaces with vars, where `<db>` must be substituted with
the database type, either `hsqldb`, `postgresql`, or `oracle`:

- `pod.babashka.<db>`:
  - `execute!`: similar to `next.jdbc/execute!`
  - `execute-one!`: similar to `next.jdbc/execute-one!`
  - `get-connection`: returns connection serialized using maps with a unique identifier key
  - `close-connection`: closes a connection returned from `get-connection`
  - `with-transaction`: similar to `next.jdbc/with-transaction`
- `pod.babashka.<db>.sql`:
  - `insert-multi!`: similar to `next.jdbc.sql/insert-multi!`
- `pod.babashka.<db>.transaction`:
  - `begin`: marks the begin of a transaction, expects connection returned from `get-connection`
  - `rollback`: rolls back transaction, expects connection returned from `get-connection`
  - `commit`: commits transaction, expects connection returned from `get-connection`

More functions from [next.jdbc](https://github.com/seancorfield/next-jdbc) can
be added. PRs welcome.

## Run

An example using `pod-babashka-postgresql`:

``` clojure
(require '[babashka.pods :as pods])

;; load from pod registry:
(pods/load-pod 'org.babashka/postgresql "0.1.0")
;; or load from system path:
;; (pods/load-pod "pod-babashka-postgresql")
;; or load from a relative or absolute path:
;; (pods/load-pod "./pod-babashka-postgresql")

(require '[pod.babashka.postgresql :as pg])

(def db {:dbtype   "postgresql"
         :host     "your-db-host-name"
         :dbname   "your-db"
         :user     "develop"
         :password "develop"
         :port     5432})

(pg/execute! db ["select version()"])
;;=> [{:version "PostgreSQL 9.5.18 on x86_64-pc-linux-gnu (Debian 9.5.18-1.pgdg90+1), compiled by gcc (Debian 6.3.0-18+deb9u1) 6.3.0 20170516, 64-bit"}]
```

An example using `pod-babashka-hsqldb`:

``` clojure
(require '[babashka.pods :as pods])

;; load from pod registry:
(pods/load-pod 'org.babashka/hsqldb "0.1.0")
;; or load from system path:
;; (pods/load-pod "pod-babashka-hsqldb")
;; or load from a relative or absolute path:
;; (pods/load-pod "./pod-babashka-hsqldb")

(require '[pod.babashka.hsqldb :as db])

(def db "jdbc:hsqldb:mem:testdb;sql.syntax_mys=true")
(db/execute! db ["create table foo ( foo int );"])
;;=> [#:next.jdbc{:update-count 0}]

(db/execute! db ["create table foo ( foo int );"])
;;=> ... error output from pod omitted
;;=> clojure.lang.ExceptionInfo: object name already exists: FOO in statement [create table foo ( foo int )] [at line 6, column 1]

(db/execute! db ["insert into foo values (1, 2, 3);"])
;;=> [#:next.jdbc{:update-count 3}]

(db/execute! db ["select * from foo;"])
;;=> [#:FOO{:FOO 1} #:FOO{:FOO 2} #:FOO{:FOO 3}]
```

A more elaborate example can be found
[here](https://github.com/borkdude/babashka/blob/2d12c954a1ef25e6ed83cde3db57be69dbb0c906/examples/hsqldb_unused_vars.clj).

### Arrays

#### Writing arrays

Inserting arrays works automatically: just pass a Java array, with e.g. `(into-array [1 2 3])`.

#### Reading arrays

Array columns automatically get converted to Clojure vectors. Converting back
into arrays must be done manually.

### JSON

This section only applies to PostgreSQL for now, but can be extended to other databases.

#### Writing JSON

- Convert to a JSON string manually and insert with `?::text`. PostgreSQL will automatically convert the parameter to the right type. Full example:

``` clojure
(db/execute! db ["insert into json_table values (?::text);" (json/generate-string {:a 1})])
```

- Use `db/write-json` or `db/write-jsonb`. Full example:

``` clojure
(db/execute! db ["insert into json_table values (?);" (db/write-json {:a 1})])
```

#### Reading JSON

- Both json and jsonb are automatically converted to Clojure values. Keys are keywordized automatically. You can override this behavior by passing a `:pod.babashka.sql/read` option to `execute!`:

``` clojure
{:pod.babashka.sql/read {:json :parse+keywordize}} ;; default
{:pod.babashka.sql/read {:json :parse}} ;; no keyword keys
{:pod.babashka.sql/read {:json :string}} ;; json as raw string
```

Use `:jsonb` to apply these options to jsonb-typed columns.

- Select column as text and deserialize the result manually:

``` clojure
(db/execute! db ["select json::text from json_table;"])
```

## Libraries

In addition to using a sql pod, the following babashka-compatible libraries might be
helpful:

### [honeysql](https://github.com/seancorfield/honeysql)

Turn Clojure data structures into SQL.

Needs babashka >= 0.2.6. Babashka is tested against HoneySQL version `1.0.444` in CI.

Example:

``` clojure
(ns honeysql-script
  (:require [babashka.deps :as deps]
            [babashka.pods :as pods]))

;; Load HoneySQL from Clojars:
(deps/add-deps '{:deps {honeysql/honeysql {:mvn/version "1.0.444"}}})

(require '[honeysql.core :as hsql])

(hsql/format {:select [:a :b :c] :from [:foo] :where [:= :a 1]})
;;=> ["SELECT a, b, c FROM foo WHERE a = ?" 1]
```

## Troubleshooting

### MS SQL Server support

If you are connecting to SQL Server, you may try connecting like this:

```clojure
(require '[pod.babashka.mssql :as sql])
(def db {:dbtype "mssql" :host "my-dbhost" :dbname "my_db" :integratedSecurity true})
(sql/execute! db ...)
```

Using integrated security like this will not work (yet?) - you will get an error:

```
----- Error --------------------------------------------------------------------
Type:     clojure.lang.ExceptionInfo
Message:  This driver is not configured for integrated authentication. ClientConnectionId:889ad681-4fdf-409c-b12c-9eef93129023
```

As a workaround, you can use [this pod](https://github.com/xledger/pod_sql_server), which connects via a .NET library.

If you are using SQL Server, you may also be interested in [this pod](https://github.com/xledger/pod_tsql_scriptdom), which has a function to reformat/indent your SQL.

## Dev

Set `POD_DB_TYPE` to either `hsqldb`, `postgresql`, or `oracle`.

### Build

Run `script/compile`

### Test

Run `script/test`.

## License

Copyright © 2020-2021 Michiel Borkent

Distributed under the EPL License. See LICENSE.

[Helidon license](https://github.com/oracle/helidon/blob/master/LICENSE.txt)
[HyperSQL license](http://hsqldb.org/web/hsqlLicense.html)
[MySQL JDBC license](https://downloads.mysql.com/docs/licenses/connector-j-8.0-gpl-en.pdf)
[MSSQL JDBC license](https://raw.githubusercontent.com/microsoft/mssql-jdbc/dev/LICENSE)
[PostgreSQL JDBC license](https://jdbc.postgresql.org/about/license.html)
