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
  (testing "builder-fn options"
    (let [db "jdbc:hsqldb:mem:builderdb;sql.syntax_mys=true"]
      (db/execute! db ["create table people ( id int, first_name varchar(32), last_name varchar(32) );"])
      (db/execute! db ["insert into people values (1, 'Alice', 'Smith'), (2, 'Bob', 'Jones');"])

      (testing "default / omitted builder-fn and nil builder-fn"
        (is (= [#:PEOPLE{:ID 1, :FIRST_NAME "Alice", :LAST_NAME "Smith"}
                #:PEOPLE{:ID 2, :FIRST_NAME "Bob", :LAST_NAME "Jones"}]
               (db/execute! db ["select * from people order by id;"])))
        (is (= [#:PEOPLE{:ID 1, :FIRST_NAME "Alice", :LAST_NAME "Smith"}
                #:PEOPLE{:ID 2, :FIRST_NAME "Bob", :LAST_NAME "Jones"}]
               (db/execute! db ["select * from people order by id;"] {:builder-fn nil})))
        (is (= #:PEOPLE{:ID 1, :FIRST_NAME "Alice", :LAST_NAME "Smith"}
               (db/execute-one! db ["select * from people where id = 1;"])))
        (is (= #:PEOPLE{:ID 1, :FIRST_NAME "Alice", :LAST_NAME "Smith"}
               (db/execute-one! db ["select * from people where id = 1;"] {:builder-fn nil}))))

      (testing "map builders with symbols and keywords"
        ;; qualified maps
        (is (= [#:PEOPLE{:ID 1, :FIRST_NAME "Alice", :LAST_NAME "Smith"}
                #:PEOPLE{:ID 2, :FIRST_NAME "Bob", :LAST_NAME "Jones"}]
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'next.jdbc.result-set/as-maps})
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'as-maps})
               (db/execute! db ["select * from people order by id;"] {:builder-fn :as-maps})))

        ;; unqualified maps
        (is (= [{:ID 1, :FIRST_NAME "Alice", :LAST_NAME "Smith"}
                {:ID 2, :FIRST_NAME "Bob", :LAST_NAME "Jones"}]
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'next.jdbc.result-set/as-unqualified-maps})
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'as-unqualified-maps})
               (db/execute! db ["select * from people order by id;"] {:builder-fn :as-unqualified-maps})))

        ;; lower maps
        (is (= [#:people{:id 1, :first_name "Alice", :last_name "Smith"}
                #:people{:id 2, :first_name "Bob", :last_name "Jones"}]
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'next.jdbc.result-set/as-lower-maps})
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'as-lower-maps})
               (db/execute! db ["select * from people order by id;"] {:builder-fn :as-lower-maps})))

        ;; unqualified lower maps
        (is (= [{:id 1, :first_name "Alice", :last_name "Smith"}
                {:id 2, :first_name "Bob", :last_name "Jones"}]
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'next.jdbc.result-set/as-unqualified-lower-maps})
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'as-unqualified-lower-maps})
               (db/execute! db ["select * from people order by id;"] {:builder-fn :as-unqualified-lower-maps})))

        ;; kebab maps
        (is (= [#:people{:id 1, :first-name "Alice", :last-name "Smith"}
                #:people{:id 2, :first-name "Bob", :last-name "Jones"}]
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'next.jdbc.result-set/as-kebab-maps})
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'as-kebab-maps})
               (db/execute! db ["select * from people order by id;"] {:builder-fn :as-kebab-maps})))

        ;; unqualified kebab maps
        (is (= [{:id 1, :first-name "Alice", :last-name "Smith"}
                {:id 2, :first-name "Bob", :last-name "Jones"}]
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'next.jdbc.result-set/as-unqualified-kebab-maps})
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'as-unqualified-kebab-maps})
               (db/execute! db ["select * from people order by id;"] {:builder-fn :as-unqualified-kebab-maps})))

        ;; execute-one! with map builders
        (is (= {:id 1, :first-name "Alice", :last-name "Smith"}
               (db/execute-one! db ["select * from people where id = 1;"] {:builder-fn :as-unqualified-kebab-maps})))
        (is (= #:people{:id 1, :first-name "Alice", :last-name "Smith"}
               (db/execute-one! db ["select * from people where id = 1;"] {:builder-fn 'next.jdbc.result-set/as-kebab-maps}))))

      (testing "array builders with symbols and keywords"
        ;; qualified arrays
        (is (= [[:PEOPLE/ID :PEOPLE/FIRST_NAME :PEOPLE/LAST_NAME]
                [1 "Alice" "Smith"]
                [2 "Bob" "Jones"]]
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'next.jdbc.result-set/as-arrays})
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'as-arrays})
               (db/execute! db ["select * from people order by id;"] {:builder-fn :as-arrays})))

        ;; unqualified arrays
        (is (= [[:ID :FIRST_NAME :LAST_NAME]
                [1 "Alice" "Smith"]
                [2 "Bob" "Jones"]]
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'next.jdbc.result-set/as-unqualified-arrays})
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'as-unqualified-arrays})
               (db/execute! db ["select * from people order by id;"] {:builder-fn :as-unqualified-arrays})))

        ;; lower arrays
        (is (= [[:people/id :people/first_name :people/last_name]
                [1 "Alice" "Smith"]
                [2 "Bob" "Jones"]]
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'next.jdbc.result-set/as-lower-arrays})
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'as-lower-arrays})
               (db/execute! db ["select * from people order by id;"] {:builder-fn :as-lower-arrays})))

        ;; unqualified lower arrays
        (is (= [[:id :first_name :last_name]
                [1 "Alice" "Smith"]
                [2 "Bob" "Jones"]]
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'next.jdbc.result-set/as-unqualified-lower-arrays})
               (db/execute! db ["select * from people order by id;"] {:builder-fn 'as-unqualified-lower-arrays})
               (db/execute! db ["select * from people order by id;"] {:builder-fn :as-unqualified-lower-arrays})))

        ;; empty result with array builders
        (is (= [[:ID :FIRST_NAME :LAST_NAME]]
               (db/execute! db ["select * from people where id = 999;"] {:builder-fn :as-unqualified-arrays})))
        (is (= nil
               (db/execute-one! db ["select * from people where id = 999;"] {:builder-fn :as-unqualified-arrays})))

        ;; execute-one! with array builders returns single row vector
        (is (= [1 "Alice" "Smith"]
               (db/execute-one! db ["select * from people where id = 1;"] {:builder-fn :as-unqualified-arrays})))
        (is (= [1 "Alice" "Smith"]
               (db/execute-one! db ["select * from people where id = 1;"] {:builder-fn 'next.jdbc.result-set/as-unqualified-lower-arrays}))))

      (testing "wide SELECT (>8 columns) array column order preservation"
        (db/execute! db ["create table wide_table (c1 int, c2 int, c3 int, c4 int, c5 int, c6 int, c7 int, c8 int, c9 int, c10 int);"])
        (db/execute! db ["insert into wide_table values (1, 2, 3, 4, 5, 6, 7, 8, 9, 10);"])
        (is (= [[:C1 :C2 :C3 :C4 :C5 :C6 :C7 :C8 :C9 :C10]
                [1 2 3 4 5 6 7 8 9 10]]
               (db/execute! db ["select c1, c2, c3, c4, c5, c6, c7, c8, c9, c10 from wide_table;"]
                            {:builder-fn :as-unqualified-arrays})))
        (is (= [[:C10 :C9 :C8 :C7 :C6 :C5 :C4 :C3 :C2 :C1]
                [10 9 8 7 6 5 4 3 2 1]]
               (db/execute! db ["select c10, c9, c8, c7, c6, c5, c4, c3, c2, c1 from wide_table;"]
                            {:builder-fn 'next.jdbc.result-set/as-unqualified-arrays})))
        (is (= [1 2 3 4 5 6 7 8 9 10]
               (db/execute-one! db ["select c1, c2, c3, c4, c5, c6, c7, c8, c9, c10 from wide_table;"]
                                {:builder-fn :as-unqualified-arrays}))))

      (testing "invalid or unknown builder-fn error handling"
        (doseq [bad-builder [:unknown-builder 'custom.ns/my-builder "invalid-string"]]
          (try
            (db/execute! db ["select * from people;"] {:builder-fn bad-builder})
            (is false "Expected exception to be thrown")
            (catch Exception e
              (let [msg (ex-message e)
                    d (ex-data e)]
                (is (re-find #"Unknown :builder-fn" msg))
                (is (re-find #"as-unqualified-arrays" msg))
                (is (= bad-builder (:builder-fn d)))
                (is (= 30 (count (:allowed-builders d))))
                (is (contains? (set (:allowed-builders d)) :as-unqualified-arrays))))))
        (try
          (db/execute-one! db ["select * from people;"] {:builder-fn :invalid})
          (is false "Expected exception to be thrown")
          (catch Exception e
            (let [msg (ex-message e)
                  d (ex-data e)]
              (is (re-find #"Unknown :builder-fn" msg))
              (is (re-find #"as-unqualified-arrays" msg))
              (is (= :invalid (:builder-fn d)))
              (is (= 30 (count (:allowed-builders d))))
              (is (contains? (set (:allowed-builders d)) :as-unqualified-arrays))))))

      (db/execute! db ["drop schema public cascade;"])))
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
