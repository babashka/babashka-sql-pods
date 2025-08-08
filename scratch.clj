(ns test
  (:require
   [babashka.deps :as deps]
   [babashka.pods :as pods]))

(pods/load-pod 'org.babashka/postgresql "0.1.4")
(deps/add-deps
 '{:deps {com.github.seancorfield/honeysql {:mvn/version "2.7.1310"}}})

(require '[clojure.pprint :as pp]
         '[honey.sql :as sql]
         '[pod.babashka.postgresql :as pg])

(def datasource
  {:dbtype "postgresql"
   :hostname "localhost"
   :dbname "bb_test"
   :user "user"
   :password "password"
   :port 5432})


#_(pg/execute! (pg/get-connection datasource) ["create table test (
    first_name varchar(255),
    last_name varchar(255)
);"])

(def select-query
  (sql/format {:select :*
               :from :test}))

(def insert-query
  (sql/format {:insert-into :test
               :columns [:first_name :last_name]
               :values [["John" "Doe"]]}))

(pg/with-transaction [conn datasource]
  (pp/pprint (pg/execute! conn select-query))
  (pp/pprint (pg/execute! conn insert-query)))
