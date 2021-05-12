(ns pod.babashka.mysql-test
  {:clj-kondo/config
   '{:lint-as {pod.babashka.mysql/with-transaction next.jdbc/with-transaction}}}
  (:require [babashka.pods :as pods]
            [clojure.test :refer [deftest is testing]])
  (:import [ch.vorburger.mariadb4j DB]
           [java.util Date Arrays]))

(pods/load-pod (if (= "native" (System/getenv "POD_TEST_ENV"))
                 "./pod-babashka-mysql"
                 ["lein" "with-profiles" "+feature/mysql"
                  "run" "-m" "pod.babashka.sql"]))

(require '[pod.babashka.mysql :as db])
(require '[pod.babashka.mysql.sql :as sql])
(require '[pod.babashka.mysql.transaction :as transaction])

(def port 33066)
(def db {:dbtype "mysql"
         :port port
         :user "mysql"
         :dbname "mysql"})

(defmacro with-start [[name-sym obj] & body]
  `(let [~name-sym ~obj]
     (try
       (.start ~name-sym)
       ~@body
       (finally
         (.stop ~name-sym)))))

(deftest mysql-test
  (with-start [_ (DB/newEmbeddedDB port)]
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
    (testing "input parameters"
      (let [conn (db/get-connection db)
            start-date (Date. 158178094000)]
        (db/with-transaction [x conn]
          (db/execute! x ["create table foo_timed (foo int, created timestamp)"])
          (db/execute! x ["insert into foo_timed values (?, ?)" 1 start-date])
          (let [result (db/execute! x ["select foo from foo_timed where created <= ?" start-date])]
            (is (= result [{:foo_timed/foo 1}]))))))
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
