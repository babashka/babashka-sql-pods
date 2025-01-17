(ns pod.babashka.duckdb-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [clojure.string :as str])
  (:import [org.duckdb DuckDBDriver]))

;; Registra o driver do DuckDB
(DuckDBDriver.)

(defn duckdb-array->vec [arr]
  (when arr
    (into [] (str/split (str/replace (str arr) #"[\[\]]" "") #", "))))

(deftest duckdb-test
  (let [db-spec {:dbtype "duckdb"
                 :dbname "test.duckdb"}]
    ;; Clean up any existing database
    (try
      (jdbc/execute! db-spec ["drop table if exists foo;"])
      (catch Exception _))
    
    (testing "basic operations"
      (is (jdbc/execute! db-spec ["create table foo (foo integer);"]))
      (is (thrown-with-msg? Exception #"already exists"
                           (jdbc/execute! db-spec ["create table foo (foo integer);"])))
      (is (jdbc/execute! db-spec ["insert into foo values (1), (2), (3);"]))
      (let [query-result (jdbc/execute! db-spec ["select * from foo;"])]
        (is (= [{:foo 1} {:foo 2} {:foo 3}] query-result))))
    
    (testing "connection"
      (with-open [conn (jdbc/get-connection db-spec)]
        (is (= [{:foo 1} {:foo 2} {:foo 3}]
               (jdbc/execute! conn ["select * from foo;"])))))
    
    (testing "transaction"
      (jdbc/with-transaction [tx db-spec]
        (jdbc/execute! tx ["insert into foo values (4);"])
        (is (= [{:foo 1} {:foo 2} {:foo 3} {:foo 4}]
               (jdbc/execute! tx ["select * from foo;"]))))
      
      (testing "rollback"
        (try
          (jdbc/with-transaction [tx db-spec]
            (jdbc/execute! tx ["insert into foo values (5);"])
            (throw (Exception. "rollback")))
          (catch Exception _))
        (is (= [{:foo 1} {:foo 2} {:foo 3} {:foo 4}]
               (jdbc/execute! db-spec ["select * from foo;"]))))
      
      (testing "with-transaction"
        (jdbc/with-transaction [tx db-spec]
          (jdbc/execute! tx ["insert into foo values (5);"])
          (jdbc/execute! tx ["insert into foo values (6), (7);"]))
        
        (is (= [{:foo 1} {:foo 2} {:foo 3} {:foo 4} 
                {:foo 5} {:foo 6} {:foo 7}]
               (jdbc/execute! db-spec ["select * from foo;"])))))
    
    (jdbc/execute! db-spec ["drop table foo;"]))
  
  (testing "list support"
    (let [db-spec {:dbtype "duckdb"
                   :dbname "test-list.duckdb"}]
      ;; Clean up any existing database
      (try
        (jdbc/execute! db-spec ["drop table if exists foo;"])
        (catch Exception _))
      
      (is (jdbc/execute! db-spec ["create table foo (foo integer[]);"]))
      (is (jdbc/execute! db-spec ["insert into foo select [1, 2, 3];"]))
      (let [result (jdbc/execute! db-spec ["select foo from foo"])
            result-vec (update-in result [0 :foo] duckdb-array->vec)]
        (is (= [{:foo ["1" "2" "3"]}] result-vec)))
      (let [result (jdbc/execute-one! db-spec ["select foo from foo"])
            result-vec (update result :foo duckdb-array->vec)]
        (is (= {:foo ["1" "2" "3"]} result-vec))))))