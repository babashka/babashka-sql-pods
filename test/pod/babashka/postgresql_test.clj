(ns pod.babashka.postgresql-test
  {:clj-kondo/config
   '{:lint-as {pod.babashka.postgresql/with-transaction next.jdbc/with-transaction}}}
  (:require [babashka.pods :as pods]
            [pod.babashka.sql.features :as features]
            [clojure.test :refer [deftest is testing]])
  (:import [com.opentable.db.postgres.embedded EmbeddedPostgres]))

(pods/load-pod (if (= "native" (System/getenv "POD_TEST_ENV"))
                 "./pod-babashka-postgresql"
                 ["lein" "with-profiles" "+feature/postgresql"
                  "run" "-m" "pod.babashka.sql"]))

(require '[pod.babashka.postgresql :as db])
(require '[pod.babashka.postgresql.transaction :as transaction])

(def port 54322)
(def db {:dbtype "postgres"
         :port port
         :user "postgres"
         :dbname "postgres"})

(deftest postgresql-test
  (with-open [_ (-> (EmbeddedPostgres/builder)
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
               (db/execute! conn  ["select * from foo;"])))
        (db/close-connection conn)))
    (testing "transaction"
      (let [conn (db/get-connection db)]
        (transaction/begin conn)
        (db/execute! conn ["insert into foo values (4);"])
        (transaction/commit conn)
        (is (= [#:foo{:foo 1} #:foo{:foo 2} #:foo{:foo 3} #:foo{:foo 4}]
               (db/execute! db  ["select * from foo;"]))))
      (testing "rollback"
        (let [conn (db/get-connection db)]
          (transaction/begin conn)
          (db/execute! conn ["insert into foo values (5);"])
          (transaction/rollback conn)
          (db/close-connection conn)
          (is (= [#:foo{:foo 1} #:foo{:foo 2} #:foo{:foo 3} #:foo{:foo 4}]
                 (db/execute! db  ["select * from foo;"])))))
      (testing "with-transaction"
        (is (= [#:next.jdbc{:update-count 2}]
               (db/with-transaction [x db]
                 (db/execute! x ["insert into foo values (5);"])
                 (db/execute! x ["insert into foo values (6), (7);"]))))
        (is (= [#:foo{:foo 1} #:foo{:foo 2} #:foo{:foo 3} #:foo{:foo 4}
                #:foo{:foo 5} #:foo{:foo 6} #:foo{:foo 7}]
               (db/execute! db  ["select * from foo;"])))
        (testing "failing transaction"
          (is (thrown-with-msg?
               Exception #"read-only"
               (db/with-transaction [x db {:read-only true}]
                 (db/execute! x ["insert into foo values (8);"]))))
          (is (= [#:foo{:foo 1} #:foo{:foo 2} #:foo{:foo 3} #:foo{:foo 4}
                  #:foo{:foo 5} #:foo{:foo 6} #:foo{:foo 7}]
                 (db/execute! db  ["select * from foo;"]))))))))
