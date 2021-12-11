(require '[babashka.pods :as pods])
(pods/load-pod ["clojure" "-M:mysql" "-m" "pod.babashka.sql"])
(require '[pod.babashka.mysql :as mysql])

;; running mysql in docker with:
;; docker run --name=mysql-pod-repro -p3306:3306 -e MYSQL_ROOT_PASSWORD=my-secret-pw -e MYSQL_DATABASE=test -e MYSQL_ROOT_HOST=% -d mysql/mysql-server:8.0.20

(def db {:dbtype "mysql"
         :host "localhost"
         :port 3306
         :dbname "test"
         :user "root"
         :password "my-secret-pw"})

(def con (mysql/get-connection db))
(prn :con con)
(mysql/execute! con ["drop table if exists foo"])
(mysql/execute! con ["create table foo (a int)"])
(mysql/execute! con ["insert into foo values (1), (2), (3)"])
(mysql/execute! con ["select * from foo"])

(mysql/with-transaction [tx db #_con]
  (prn :tx1 tx)
  ;; (Thread/sleep 200)
  (prn (mysql/execute! tx ["select * from foo"])))

(prn :after1)

(mysql/with-transaction [tx db #_con]
  (prn :tx2 tx)
  (Thread/sleep 200)
  (prn (mysql/execute! tx ["select * from foo"])))

(prn :after2)

(mysql/with-transaction [tx db #_con]
  (prn :tx2 tx)
  (Thread/sleep 200)
  (prn (mysql/execute! tx ["select * from foo"])))

(prn :after3)

(mysql/with-transaction [tx db #_con]
  (prn :tx2 tx)
  ;; (Thread/sleep 200)
  (prn (mysql/execute! tx ["select * from foo"])))
