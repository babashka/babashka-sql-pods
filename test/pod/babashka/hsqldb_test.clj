(ns pod.babashka.hsqldb-test
  {:clj-kondo/config
   '{:lint-as {pod.babashka.hsqldb/with-transaction next.jdbc/with-transaction}}}
  (:require [babashka.pods :as pods]
            [clojure.test :refer [deftest is testing]]))

(pods/load-pod (if (= "native" (System/getenv "POD_TEST_ENV"))
                 "./pod-babashka-hsqldb"
                 ["lein" "with-profiles" "+feature/hsqldb"
                  "run" "-m" "pod.babashka.sql"]))

(require '[pod.babashka.hsqldb :as db])
(require '[pod.babashka.hsqldb.transaction :as transaction])

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
          (is (= [#:next.jdbc{:update-count 2}]
                 (db/with-transaction [x db]
                   (db/execute! x ["insert into foo values (5);"])
                   (db/execute! x ["insert into foo values (6, 7);"]))))
          (is (= [#:FOO{:FOO 1} #:FOO{:FOO 2} #:FOO{:FOO 3}
                  #:FOO{:FOO 4} #:FOO{:FOO 5} #:FOO{:FOO 6}
                  #:FOO{:FOO 7}]
                 (db/execute! db  ["select * from foo;"])))
          (testing "failing transaction"
            (is (thrown-with-msg?
                 Exception #"read-only SQL-transaction"
                 (db/with-transaction [x db {:read-only true}]
                   (db/execute! x ["insert into foo values (8);"]))))
            (is (= [#:FOO{:FOO 1} #:FOO{:FOO 2} #:FOO{:FOO 3}
                    #:FOO{:FOO 4} #:FOO{:FOO 5} #:FOO{:FOO 6}
                    #:FOO{:FOO 7}]
                   (db/execute! db  ["select * from foo;"]))))))
      (db/execute! db ["drop schema public cascade;"]))
  (let [db "jdbc:hsqldb:mem:testdb;sql.syntax_mys=true"]
    (is (db/execute! db ["create table foo ( foo integer array );"]))
    (is (db/execute! db ["insert into foo (foo) values (?);" (into-array [1 2 3])]))
    (is (= [#:FOO{:FOO [1 2 3]}]
           (db/execute! db ["select * from foo"])))
    (is (= #:FOO{:FOO [1 2 3]}
           (db/execute-one! db ["select * from foo"]))))
  (testing "concurrent requests"
    (let [db "jdbc:hsqldb:mem:concurrentdb"
          n 20]
      (db/execute! db ["create table bar ( id int, val int );"])
      (let [futures (mapv (fn [i]
                            (future (db/execute! db [(str "insert into bar values (" i ", " (* i 10) ");")])))
                          (range n))]
        (run! deref futures))
      (let [result (db/execute! db ["select count(*) as cnt from bar;"])]
        (is (= n (:CNT (first result)))))
      (db/execute! db ["drop schema public cascade;"])))
  (testing "concurrent connections"
    (let [db "jdbc:hsqldb:mem:concurrentconnsdb"
          n 20]
      (db/execute! db ["create table baz ( id int, val int );"])
      (let [conns (mapv (fn [_] (future (db/get-connection db))) (range n))
            conns (mapv deref conns)]
        (let [futures (mapv (fn [i]
                              (future (db/execute! (nth conns i)
                                                   [(str "insert into baz values (" i ", " (* i 10) ");")])))
                            (range n))]
          (run! deref futures))
        (let [result (db/execute! db ["select count(*) as cnt from baz;"])]
          (is (= n (:CNT (first result)))))
        (let [futures (mapv (fn [conn] (future (db/close-connection conn))) conns)]
          (run! deref futures)))
      (db/execute! db ["drop schema public cascade;"])))
  (testing "concurrent transactions"
    (let [db "jdbc:hsqldb:mem:concurrenttxdb"
          n 10]
      (db/execute! db ["create table concurrent_transaction_test ( id int, val int );"])
      (let [futures (mapv (fn [i]
                            (future
                              (let [conn (db/get-connection db)]
                                (transaction/begin conn)
                                (db/execute! conn [(str "insert into concurrent_transaction_test values (" i ", " (* i 10) ");")])
                                (transaction/commit conn)
                                (db/close-connection conn))))
                          (range n))]
        (run! deref futures))
      (let [result (db/execute! db ["select count(*) as cnt from concurrent_transaction_test;"])]
        (is (= n (:CNT (first result)))))
      (testing "concurrent rollbacks"
        (let [before-count (:CNT (first (db/execute! db ["select count(*) as cnt from concurrent_transaction_test;"])))
              futures (mapv (fn [i]
                              (future
                                (let [conn (db/get-connection db)]
                                  (transaction/begin conn)
                                  (db/execute! conn [(str "insert into concurrent_transaction_test values (" (+ n i) ", 999);")])
                                  (transaction/rollback conn)
                                  (db/close-connection conn))))
                            (range n))]
          (run! deref futures)
          (let [after-count (:CNT (first (db/execute! db ["select count(*) as cnt from concurrent_transaction_test;"])))]
            (is (= before-count after-count)))))
      (db/execute! db ["drop schema public cascade;"]))))
