(ns pod.babashka.postgresql-test
  {:clj-kondo/config
   '{:lint-as {pod.babashka.postgresql/with-transaction next.jdbc/with-transaction}}}
  (:require [babashka.pods :as pods]
            [clojure.test :refer [deftest is testing]])
  (:import [io.zonky.test.db.postgres.embedded EmbeddedPostgres]
           [java.util Date Arrays]
           [java.time Duration]))

(pods/load-pod (if (= "native" (System/getenv "POD_TEST_ENV"))
                 "./pod-babashka-postgresql"
                 ["lein" "with-profiles" "+feature/postgresql"
                  "run" "-m" "pod.babashka.sql"]))

(require '[pod.babashka.postgresql :as db])
(require '[pod.babashka.postgresql.sql :as sql])
(require '[pod.babashka.postgresql.transaction :as transaction])

(def port 54322)

(def db {:dbtype "postgres"
         :user "postgres"
         :port port
         :dbname "postgres"})

(deftest postgresql-test
  (with-open [_conn (-> (EmbeddedPostgres/builder)
                        (.setPort port)
                        .start
                        .getPostgresDatabase
                        .getConnection)]
    (is (db/execute! db ["create table foo ( foo int );"]))
    (is (thrown-with-msg? Exception #"exists"
                          (db/execute! db ["create table foo ( foo int );"])))
    (is (db/execute! db ["insert into foo values (1), (2), (3);"]))
    (let [query-result (db/execute! db ["select * from foo;"])]
      (is (= [#:foo{:foo 1} #:foo{:foo 2} #:foo{:foo 3}] query-result)))
    (testing "connection"
      (let [conn (db/get-connection db)]
        (is (= [#:foo{:foo 1} #:foo{:foo 2} #:foo{:foo 3}]
               (db/execute! conn ["select * from foo;"])))
        (db/close-connection conn)))
    (testing "input parameters"
      (let [conn (db/get-connection db)
            start-date (Date. 158178094000)]
        (db/with-transaction [x conn]
          (db/execute! x ["create table foo_timed (foo int, created timestamp with time zone)"])
          (db/execute! x ["insert into foo_timed values (?, ?)" 1 start-date])
          (let [result (db/execute! x ["select foo from foo_timed where created <= ?" start-date])]
            (is (= result [{:foo_timed/foo 1}]))))))
    (testing "transaction"
      (let [conn (db/get-connection db)]
        (transaction/begin conn)
        (db/execute! conn ["insert into foo values (4);"])
        (transaction/commit conn)
        (is (= [#:foo{:foo 1} #:foo{:foo 2} #:foo{:foo 3} #:foo{:foo 4}]
               (db/execute! db ["select * from foo;"]))))
      (testing "rollback"
        (let [conn (db/get-connection db)]
          (transaction/begin conn)
          (db/execute! conn ["insert into foo values (5);"])
          (transaction/rollback conn)
          (db/close-connection conn)
          (is (= [#:foo{:foo 1} #:foo{:foo 2} #:foo{:foo 3} #:foo{:foo 4}]
                 (db/execute! db ["select * from foo;"])))))
      (testing "with-transaction"
        (dotimes [_ 10]
          (is (= [#:next.jdbc{:update-count 2}]
                 (db/with-transaction [x db]
                   (db/execute! x ["insert into foo values (5);"])
                   (db/execute! x ["insert into foo values (6), (7);"])))))
        (is (= 2 (count (db/execute! db ["select distinct foo from foo where foo > 5;"]))))
        (testing "failing transaction"
          (is (thrown-with-msg?
               Exception #"read-only"
               (db/with-transaction [x db {:read-only true}]
                 (db/execute! x ["insert into foo values (8);"]))))
          (is (zero? (count (db/execute! db ["select * from foo where foo = 8;"])))))))
    (testing "arrays"
      (testing "byte arrays"
        (let [bs (.getBytes "foo")]
          (is (db/execute! db ["create table bytes ( bs bytea );"]))
          (is (db/execute! db ["insert into bytes values (?);" bs]))
          (is (Arrays/equals bs (:bytes/bs (db/execute-one! db ["select * from bytes;"]))))))
      (testing "non-byte arrays"
        (is (db/execute! db ["create table bar ( bar integer[] );"]))
        (is (db/execute! db ["insert into bar values (?);" (into-array [1 2 3])]))
        (is (= [#:bar{:bar [1 2 3]}] (db/execute! db ["select * from bar"])))
        (is (db/execute! db ["create table baz ( baz text[] );"]))
        (is (db/execute! db ["insert into baz values (?);" (into-array ["foo" "bar"])]))
        (is (= [#:baz{:baz ["foo" "bar"]}] (db/execute! db ["select * from baz"])))
        (is (= #:baz{:baz ["foo" "bar"]} (db/execute-one! db ["select * from baz"])))
        (is (= [#:baz{:baz ["a" "b"]} #:baz{:baz ["x" "y"]}]
               (sql/insert-multi! db :baz [:baz] [[(into-array ["a" "b"])]
                                                  [(into-array ["x" "y"])]])))))
    (testing "json"
      (is (db/execute! db ["create table json_table ( json_col json );"]))
      (is (db/execute! db ["insert into json_table values (?);" (db/write-json {:a 1})]))
      (is (= [#:json_table{:json_col {:a 1}}] (db/execute! db ["select * from json_table values;"])))
      (is (= [#:json_table{:json_col {:a 1}}]
             (db/execute! db ["select * from json_table values;"]
                          {:pod.babashka.sql/read {:json :parse+keywordize}})))
      (is (= [#:json_table{:json_col {"a" 1}}]
             (db/execute! db ["select * from json_table values;"]
                          {:pod.babashka.sql/read {:json :parse}})))
      (is (= [#:json_table{:json_col "{\"a\":1}"}]
             (db/execute! db ["select * from json_table values;"]
                          {:pod.babashka.sql/read {:json :string}}))))
    (testing "jsonb"
      (is (db/execute! db ["create table jsonb_table ( jsonb_col jsonb );"]))
      (is (db/execute! db ["insert into jsonb_table values (?);" (db/write-jsonb {:a 1})]))
      (is (= [#:jsonb_table{:jsonb_col {:a 1}}] (db/execute! db ["select * from jsonb_table values;"])))
      (is (= [#:jsonb_table{:jsonb_col {:a 1}}]
             (db/execute! db ["select * from jsonb_table values;"]
                          {:pod.babashka.sql/read {:jsonb :parse+keywordize}})))
      (is (= [#:jsonb_table{:jsonb_col {"a" 1}}]
             (db/execute! db ["select * from jsonb_table values;"]
                          {:pod.babashka.sql/read {:jsonb :parse}})))
      (is (= [#:jsonb_table{:jsonb_col "{\"a\": 1}"}]
             (db/execute! db ["select * from jsonb_table values;"]
                          {:pod.babashka.sql/read {:jsonb :string}}))))
    (testing "interval"
      (is (db/execute! db ["create table interval_table (interval_col interval);"]))
      (is (db/execute! db ["insert into interval_table(interval_col) values ('00:00:00'::interval);"]))
      (is (= [#:interval_table{:interval_col "0 years 0 mons 0 days 0 hours 0 mins 0.0 secs"}]
             (db/execute! db ["SELECT interval_col from interval_table;"]))))))
