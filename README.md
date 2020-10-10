# Babashka sql pods

[Babashka](https://github.com/borkdude/babashka) pods for interacting with SQL
databases.

Supported databases:

- [HSQLDB](http://www.hsqldb.org/)
- [PostgresQL](https://www.postgresql.org/)

PRs for other SQL databases are welcome.

## Install

The following installation methods are available:

- Download a binary from Github releases
- With [brew](https://brew.sh/): `brew install borkdude/brew/pod-babashka-<db>`
where `<db>` must be substited with the database type, either `hsqldb` or
`postgresql`.

## Compatibility

Pods from this repo require babashka v0.0.96 or later.

## Available vars

The pods expose these namespaces with vars, where `<db>` must be substited with
the database type, either `hsqldb` or `postgresql`:

- `pod.babashka.<db>`:
  - `execute!`: similar to `next.jdbc/execute!`
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

(pods/load-pod "pod-babashka-postgresql")
;; note: if the pod is downloaded directly to your file system, `load-pod`
;; needs to be told explicitly where to find it. The form below assumes
;; that the pod was downloaded and lives in the same directory as your script.
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

(pods/load-pod "pod-babashka-hsqldb")
;; note: if the pod is downloaded directly to your file system, `load-pod`
;; needs to be told explicitly where to find it. The form below assumes
;; that the pod was downloaded and lives in the same directory as your script.
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

## Libraries

In addition to using a sql pod, the following babashka-compatible libraries might be
helpful:

### [honeysql](https://github.com/seancorfield/honeysql)

Turn Clojure data structures into SQL.

Needs babashka >= 0.1.2. Babashka is tested against HoneySQL version `1.0.444` in CI.

Example:

``` clojure
$ export BABASHKA_CLASSPATH=$(clojure -Sdeps '{:deps {honeysql {:mvn/version "1.0.444"}}}' -Spath)
$ rlwrap bb

user=> (require '[honeysql.core :as hsql])
nil
user=> (hsql/format {:select [:a :b :c] :from [:foo] :where [:= :a 1]})
["SELECT a, b, c FROM foo WHERE a = ?" 1]
```

## Dev

Set `POD_DB_TYPE` to either `hsqldb` or `postgresql`.

### Build

Run `script/compile`

### Test

Run `script/test`.

## License

Copyright © 2020 Michiel Borkent

Distributed under the EPL License. See LICENSE.

[HyperSQL license](http://hsqldb.org/web/hsqlLicense.html)
