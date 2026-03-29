(ns pod.babashka.db2-test
  {:clj-kondo/config
   '{:lint-as {pod.babashka.db2/with-transaction next.jdbc/with-transaction}}}
  (:require [babashka.pods :as pods]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;; Connection config is read from db2_config.edn next to this file.
;; It must contain a plain JDBC URL string, e.g.:
;; "jdbc:db2://your-host:50000/your-db:user=your-user;password=your-password;"

(pods/load-pod (if (= "native" (System/getenv "POD_TEST_ENV"))
                 "./pod-babashka-db2"
                 ["lein" "with-profiles" "+feature/db2"
                  "run" "-m" "pod.babashka.sql"]))

(require '[pod.babashka.db2 :as db])
(require '[pod.babashka.db2.sql :as sql])
(require '[pod.babashka.db2.transaction :as transaction])

(def db
  (-> (io/resource "pod/babashka/db2_config.edn")
      io/file
      slurp
      edn/read-string
      :db))

(def test-table "POD_BB_DB2_TEST")

(deftest db2-crud-test
  ;; Drop leftover table from any previous failed run
  (try (db/execute! db [(str "DROP TABLE " test-table)]) (catch Exception _))

  (try
    (testing "create table"
      (is (db/execute! db [(str "CREATE TABLE " test-table
                                " (id INTEGER, val VARCHAR(100))")])))

    (testing "insert rows"
      (db/execute! db [(str "INSERT INTO " test-table " VALUES (1, 'hello')")])
      (db/execute! db [(str "INSERT INTO " test-table " VALUES (2, 'world')")])
      (db/execute! db [(str "INSERT INTO " test-table " VALUES (3, 'foo')")])
      (let [rows (db/execute! db [(str "SELECT * FROM " test-table " ORDER BY id")])]
        (is (= 3 (count rows)))))

    (testing "connection and close"
      (let [conn (db/get-connection db)]
        (is (map? conn))
        (db/execute! conn [(str "INSERT INTO " test-table " VALUES (4, 'bar')")])
        (let [rows (db/execute! conn [(str "SELECT * FROM " test-table " ORDER BY id")])]
          (is (= 4 (count rows))))
        (db/close-connection conn)))

    (testing "transaction commit"
      (let [conn (db/get-connection db)]
        (transaction/begin conn)
        (db/execute! conn [(str "INSERT INTO " test-table " VALUES (5, 'txn')")])
        (transaction/commit conn)
        (db/close-connection conn)
        (let [rows (db/execute! db [(str "SELECT * FROM " test-table " ORDER BY id")])]
          (is (= 5 (count rows))))))

    (testing "transaction rollback"
      (let [conn (db/get-connection db)]
        (transaction/begin conn)
        (db/execute! conn [(str "INSERT INTO " test-table " VALUES (99, 'rollback')")])
        (transaction/rollback conn)
        ;; DB2 is strict: wait for the async rollback to complete before closing
        (Thread/sleep 300)
        (db/close-connection conn)
        (let [rows (db/execute! db [(str "SELECT * FROM " test-table " ORDER BY id")])]
          (is (= 5 (count rows))))))

    (testing "with-transaction"
      (db/with-transaction [tx db]
        (db/execute! tx [(str "INSERT INTO " test-table " VALUES (6, 'wtxn1')")])
        (db/execute! tx [(str "INSERT INTO " test-table " VALUES (7, 'wtxn2')")]))
      (let [rows (db/execute! db [(str "SELECT * FROM " test-table " ORDER BY id")])]
        (is (= 7 (count rows)))))

    (testing "with-transaction rollback on exception"
      (is (thrown? Exception
                   (db/with-transaction [tx db]
                     (db/execute! tx [(str "INSERT INTO " test-table " VALUES (8, 'fail')")])
                     (throw (ex-info "forced rollback" {})))))
      (let [rows (db/execute! db [(str "SELECT * FROM " test-table " ORDER BY id")])]
        (is (= 7 (count rows)))))

    (finally
      (db/execute! db [(str "DROP TABLE " test-table)]))))
