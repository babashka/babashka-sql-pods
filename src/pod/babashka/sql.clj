(ns pod.babashka.sql
  (:refer-clojure :exclude [read read-string])
  (:require [bencode.core :as bencode]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [cognitect.transit :as transit]
            [next.jdbc :as jdbc]
            [next.jdbc.date-time]
            [next.jdbc.sql :as sql]
            [next.jdbc.transaction :as t]
            [pod.babashka.sql.features :as features])
  (:import [java.io PushbackInputStream])
  (:gen-class))

(def stdin (PushbackInputStream. System/in))

(defn write [v]
  (bencode/write-bencode System/out v)
  (.flush System/out))

(defn read-string [^"[B" v]
  (String. v))

(defn read []
  (bencode/read-bencode stdin))

(def debug? false)

(defn debug [& strs]
  (when debug?
    (binding [*out* (io/writer System/err)]
      (apply println strs))))

(def conns (atom {}))
(def transactions (atom {}))

(defn get-connection [db-spec]
  (if (and (map? db-spec) (::connection db-spec))
    db-spec
    (let [conn (jdbc/get-connection db-spec)
          conn-id (str (java.util.UUID/randomUUID))]
      (swap! conns assoc conn-id conn)
      {::connection conn-id})))

(defn ->connectable [db-spec]
  (if-let [conn-id (and (map? db-spec)
                        (::connection db-spec))]
    (get @conns conn-id)
    db-spec))

(defn execute! [db-spec & args]
  (let [conn (->connectable db-spec)]
    (apply jdbc/execute! conn args)))

(defn execute-one! [db-spec & args]
  (let [conn (->connectable db-spec)]
    (apply jdbc/execute-one! conn args)))

(defn close-connection [{:keys [::connection]}]
  (let [[old _new] (swap-vals! conns dissoc connection)]
    (when-let [conn (get old connection)]
      (.close conn))))

(def transact @#'t/transact*)

(defn transaction-begin
  ([conn] (transaction-begin conn nil))
  ([{:keys [::connection] :as conn} opts]
   (let [prom (promise)]
     (swap! transactions assoc connection prom)
     (future
       (try
         (transact (->connectable conn)
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

(defn transaction-rollback [{:keys [::connection]}]
  (let [[old _new] (swap-vals! transactions dissoc connection)
        prom (get old connection)]
    (deliver prom ::rollback)
    nil))

(defn transaction-commit [{:keys [::connection]}]
  (let [[old _new] (swap-vals! transactions dissoc connection)
        prom (get old connection)]
    (deliver prom :ok)
    nil))

(def sql-ns (cond features/postgresql? "pod.babashka.postgresql"
                  features/hsqldb? "pod.babashka.hsqldb"
                  features/oracle? "pod.babashka.oracle"
                  features/mssql? "pod.babashka.mssql"
                  :else (throw (Exception. "Feature flag expected."))))

(def lookup
  (let [m {'execute! execute!
           'execute-one! execute-one!
           'get-connection get-connection
           'close-connection close-connection
           'transaction/begin transaction-begin
           'transaction/rollback transaction-rollback
           'transaction/commit transaction-commit
           'sql/insert-multi! sql/insert-multi!}]
    (zipmap (map (fn [sym]
                   (if-let [ns (namespace sym)]
                     (symbol (str sql-ns "." ns) (name sym))
                     (symbol sql-ns (name sym))))
                 (keys m))
            (vals m))))

(def with-transaction
  (-> (io/file "resources" "with_transaction.clj")
      slurp
      (str/replace "pod.babashka.sql" sql-ns)))

(def describe-map
  (walk/postwalk
   (fn [v]
     (if (ident? v) (name v)
         v))
   `{:format :transit+json
     :namespaces [{:name ~(symbol sql-ns)
                   :vars [{:name execute!}
                          {:name get-connection}
                          {:name close-connection}
                          {:name with-transaction
                           :code ~with-transaction}]}
                  {:name ~(symbol (str sql-ns ".transaction"))
                   :vars [{:name begin}
                          {:name rollback}
                          {:name commit}]}
                  {:name ~(symbol (str sql-ns ".sql"))
                   :vars [{:name insert-multi!}]}]
     :opts {:shutdown {}}}))

(debug describe-map)

(defn read-transit [^String v]
  (transit/read
   (transit/reader
    (java.io.ByteArrayInputStream. (.getBytes v "utf-8"))
    :json)))

(defn write-transit [v]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (transit/write (transit/writer baos :json) v)
    (.toString baos "utf-8")))

(defn -main [& _args]
  (loop []
    (let [message (try (read)
                       (catch java.io.EOFException _
                         ::EOF))]
      (when-not (identical? ::EOF message)
        (let [op (get message "op")
              op (read-string op)
              op (keyword op)
              id (some-> (get message "id")
                         read-string)
              id (or id "unknown")]
          (case op
            :describe (do (write describe-map)
                          (recur))
            :invoke (do (try
                          (let [var (-> (get message "var")
                                        read-string
                                        symbol)
                                args (get message "args")
                                args (read-string args)
                                args (read-transit args)]
                            (if-let [f (lookup var)]
                              (let [value (write-transit (apply f args))
                                    reply {"value" value
                                           "id" id
                                           "status" ["done"]}]
                                (write reply))
                              (throw (ex-info (str "Var not found: " var) {}))))
                          (catch Throwable e
                            (debug e)
                            (let [reply {"ex-message" (ex-message e)
                                         "ex-data" (write-transit
                                                    (assoc (ex-data e)
                                                           :type (str (class e))))
                                         "id" id
                                         "status" ["done" "error"]}]
                              (write reply))))
                        (recur))
            :shutdown (System/exit 0)
            (do
              (let [reply {"ex-message" "Unknown op"
                           "ex-data" (pr-str {:op op})
                           "id" id
                           "status" ["done" "error"]}]
                (write reply))
              (recur))))))))
