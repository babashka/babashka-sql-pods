(ns pod.babashka.hsqldb-test
  (:require [babashka.pods :as pods]
            [clojure.test :refer [deftest is]]))

(pods/load-pod (if (= "native" (System/getenv "POD_TEST_ENV"))
                 "./pod-babashka-hsqldb"
                 ["lein" "run" "-m" "pod.babashka.hsqldb"]))

(require '[pod.babashka.hsqldb :as sql])
(require '[clojure.repl :refer [dir]])
(dir sql)

(deftest hsqldb-test
  (let [db "jdbc:hsqldb:mem:testdb;sql.syntax_mys=true"]
    (is (sql/execute! db ["create table foo ( foo int );"]))
    (is (thrown-with-msg? Exception #"exists"
                          (sql/execute! db ["create table foo ( foo int );"])))
    (is (sql/execute! db ["insert into foo values (1, 2, 3);"]))
    (let [query-result (sql/execute! db ["select * from foo;"])]
      (is (= [#:FOO{:FOO 1} #:FOO{:FOO 2} #:FOO{:FOO 3}] query-result)))
    (let [conn (sql/get-connection db)]
      (is (= [#:FOO{:FOO 1} #:FOO{:FOO 2} #:FOO{:FOO 3}]
             (sql/execute! conn  ["select * from foo;"])))
      (sql/close-connection conn))))
