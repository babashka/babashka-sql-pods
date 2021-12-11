#_(require '[babashka.pods :as pods])
#_(pods/load-pod ["clojure" "-M:mysql" "-m" "pod.babashka.sql"])
#_(require '[pod.babashka.mysql :as mysql])
(require '[next.jdbc :as mysql]
         '[next.jdbc.transaction :as transaction])

;; running mysql in docker with:
;; docker run --name=mysql-pod-repro -p3306:3306 -e MYSQL_ROOT_PASSWORD=my-secret-pw -e MYSQL_DATABASE=test -e MYSQL_ROOT_HOST=% -d mysql/mysql-server:8.0.20

(def db {:dbtype "mysql"
         :host "localhost"
         :port 3306
         :dbname "test"
         :user "root"
         :password "my-secret-pw"})

(def conns (atom {}))
(def transactions (atom {}))

(defn get-connection [db-spec]
  (if (and (map? db-spec) (::connection db-spec))
    db-spec
    (let [conn (mysql/get-connection db-spec)
          conn-id (str (java.util.UUID/randomUUID))]
      (swap! conns assoc conn-id conn)
      {::connection conn-id})))

(defn ->connectable [db-spec]
  (if-let [conn-id (and (map? db-spec)
                        (::connection db-spec))]
    (get @conns conn-id)
    db-spec))

(defn transaction-begin
  ([conn] (transaction-begin conn nil))
  ([{:keys [::connection] :as conn} opts]
   (let [prom (promise)]
     (swap! transactions assoc connection prom)
     (future
       (try
         (#'transaction/transact* (->connectable conn)
                   (fn [_conn]
                     ;; wait for promise to be delivered as a result of
                     ;; calling end-transaction
                     (let [v @prom]
                       (when (identical? ::rollback v)
                         (throw (ex-info "rollback" {::rollback true})))))
                   opts)
         (catch clojure.lang.ExceptionInfo e
           (when-not (::rollback (ex-data e))
             (throw e)))))
     nil)))

(defn transaction-commit [{:keys [::connection]}]
  (let [[old _new] (swap-vals! transactions dissoc connection)
        prom (get old connection)]
    (deliver prom :ok)
    nil))

(defn transaction-rollback [{:keys [::connection]}]
  (let [[old _new] (swap-vals! transactions dissoc connection)
        prom (get old connection)]
    (deliver prom ::rollback)
    nil))

(defmacro with-transaction
  [[sym transactable opts] & body]
  `(let [~sym (get-connection ~transactable)]
     (prn :with-transact-sym ~sym)
     (try
       (prn :with-transact-begin)
       (transaction-begin ~sym ~opts)
       (prn :with-transact-exec '~body)
       (let [res# (do ~@body)]
         (prn :with-transact-commit)
         (transaction-commit ~sym)
         (prn :with-transact-commit-done)
         res#)
       (catch Exception e#
         (transaction-rollback ~sym)
         (throw e#)))))

(defn execute!
  ([db-spec sql-params]
   (execute! db-spec sql-params nil))
  ([db-spec sql-params opts]
   ;; (.println System/err (str sql-params))
   (let [conn (->connectable db-spec)
         res (try (mysql/execute! conn sql-params opts)
                  (catch Exception e
                    (throw e)))]
     res)))

(def con (get-connection db))
(prn :con con)
(execute! con ["drop table if exists foo"])
(execute! con ["create table foo (a int)"])
(execute! con ["insert into foo values (1), (2), (3)"])
(execute! con ["select * from foo"])

(with-transaction [tx db #_con]
  (prn :tx1 tx)
  ;; (Thread/sleep 200)
  (prn (execute! tx ["select * from foo"])))

(prn :after1)

(with-transaction [tx db #_con]
  (prn :tx2 tx)
  (Thread/sleep 200)
  (prn (execute! tx ["select * from foo"])))

(prn :after2)

(with-transaction [tx db #_con]
  (prn :tx2 tx)
  (Thread/sleep 200)
  (prn (execute! tx ["select * from foo"])))

(prn :after3)

(mysql/with-transaction [tx db #_con]
  (prn :tx2 tx)
  ;; (Thread/sleep 200)
  (prn (execute! tx ["select * from foo"])))

#_(shutdown-agents)
(System/exit 0)
