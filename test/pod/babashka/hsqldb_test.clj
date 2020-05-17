(ns pod.babashka.hsqldb-test
  {:clj-kondo/config
   '{:lint-as {pod.babashka.hsqldb-test/with-transaction next.jdbc/with-transaction}}}
  (:require [babashka.pods :as pods]
            [clojure.test :refer [deftest is testing]]))

(pods/load-pod (if (= "native" (System/getenv "POD_TEST_ENV"))
                 "./pod-babashka-hsqldb"
                 ["lein" "run" "-m" "pod.babashka.hsqldb"]))

(require '[pod.babashka.hsqldb :as db])
(require '[pod.babashka.hsqldb.transaction :as transaction])

(defmacro with-transaction
  [[sym transactable opts] & body]
  `(let [~sym (db/get-connection ~transactable)]
    (try
      (transaction/begin ~sym ~opts)
      ~@body
      (transaction/commit ~sym)
      (catch Exception e#
        (transaction/rollback ~sym)
        (throw e#)))))

(deftest hsqldb-test
  (let [db "jdbc:hsqldb:mem:testdb;sql.syntax_mys=true"]
    (is (db/execute! db ["create table foo ( foo int );"]))
    (is (thrown-with-msg? Exception #"exists"
                          (db/execute! db ["create table foo ( foo int );"])))
    (is (db/execute! db ["insert into foo values (1, 2, 3);"]))
    (let [query-result (db/execute! db ["select * from foo;"])]
      (is (= [#:FOO{:FOO 1} #:FOO{:FOO 2} #:FOO{:FOO 3}] query-result)))
    (testing "connection"
      (let [conn (db/get-connection db)]
        (is (= [#:FOO{:FOO 1} #:FOO{:FOO 2} #:FOO{:FOO 3}]
               (db/execute! conn  ["select * from foo;"])))
        (db/close-connection conn)))
    (testing "transaction"
      (let [conn (db/get-connection db)]
        (transaction/begin conn)
        (db/execute! conn ["insert into foo values (4);"])
        (transaction/commit conn)
        (is (= [#:FOO{:FOO 1} #:FOO{:FOO 2} #:FOO{:FOO 3} #:FOO{:FOO 4}]
               (db/execute! db  ["select * from foo;"]))))
      (testing "rollback"
        (let [conn (db/get-connection db)]
          (transaction/begin conn)
          (db/execute! conn ["insert into foo values (5);"])
          (transaction/rollback conn)
          (db/close-connection conn)
          (is (= [#:FOO{:FOO 1} #:FOO{:FOO 2} #:FOO{:FOO 3} #:FOO{:FOO 4}]
                 (db/execute! db  ["select * from foo;"])))))
      (testing "with-transaction"
        (with-transaction [x db]
          (db/execute! x ["insert into foo values (5);"]))
        (is (= [#:FOO{:FOO 1} #:FOO{:FOO 2} #:FOO{:FOO 3} #:FOO{:FOO 4} #:FOO{:FOO 5}]
               (db/execute! db  ["select * from foo;"])))
        (testing "failing transaction"
          (is (thrown-with-msg?
               Exception #"read-only SQL-transaction"
               (with-transaction [x db {:read-only true}]
                 (db/execute! x ["insert into foo values (5);"]))))
          (is (= [#:FOO{:FOO 1} #:FOO{:FOO 2} #:FOO{:FOO 3} #:FOO{:FOO 4} #:FOO{:FOO 5}]
                 (db/execute! db  ["select * from foo;"]))))))))
