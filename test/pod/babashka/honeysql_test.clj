(ns pod.babashka.honeysql-test
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
(require '[pod.babashka.postgresql.honeysql :as hsql])

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
    (prn (hsql/format {:select [:a :b :c]}))))
