(ns pod.babashka.db2-test
  {:clj-kondo/config
   '{:lint-as {pod.babashka.db2/with-transaction next.jdbc/with-transaction}}}
  (:require [babashka.pods :as pods]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import [org.testcontainers.containers Db2Container]))

;; Connection resolution order:
;; 1. db2_config.edn  — for manual / local testing against a real DB2 server
;; 2. Testcontainer    — auto-starts icr.io/db2_community/db2 via Docker/Podman
;;    Requires DOCKER_HOST env var for Podman; works out-of-the-box with Docker.
;; 3. Skip             — no DB2 available, tests pass vacuously

(pods/load-pod (if (= "native" (System/getenv "POD_TEST_ENV"))
                 "./pod-babashka-db2"
                 ["lein" "with-profiles" "+feature/db2"
                  "run" "-m" "pod.babashka.sql"]))

(require '[pod.babashka.db2 :as db])
(require '[pod.babashka.db2.sql :as sql])
(require '[pod.babashka.db2.transaction :as transaction])

(defn read-config []
  (when-let [r (io/resource "pod/babashka/db2_config.edn")]
    (-> r io/file slurp edn/read-string :db)))

(def ^:private db2-container (atom nil))

(defn start-testcontainer []
  (try
    (let [c (doto (Db2Container. "icr.io/db2_community/db2:11.5.8.0")
              (.acceptLicense)
              (.withPrivilegedMode true)
              (.start))]
      (reset! db2-container c)
      (str (.getJdbcUrl c) ":user=" (.getUsername c) ";password=" (.getPassword c) ";"))
    (catch Exception e
      (println "DB2 testcontainer unavailable:" (.getMessage e))
      nil)))

(def db
  (or (read-config)
      (start-testcontainer)))

(def test-table "POD_BB_DB2_TEST")

(deftest db2-crud-test
  (if-not db
    (println "SKIP: No DB2 connection available (no db2_config.edn and no Docker)")
    (do
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
          (db/execute! db [(str "DROP TABLE " test-table)])
          (when-let [c @db2-container]
            (.stop c)))))))
