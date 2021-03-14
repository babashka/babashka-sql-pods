(ns pod.babashka.sql
  (:refer-clojure :exclude [read read-string])
  (:require [bencode.core :as bencode]
            [cheshire.core :as json]
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

;; default implementation
(defn coerce [v as]
  (case as
    :array
    (into-array v)))

(defmacro if-pg [then else]
  (if features/postgresql?
    then
    else))

(defmacro when-pg [& body]
  `(if-pg (do ~@body) nil))

(when-pg
    (defn coerce [v as]
      (case as
        (:json :jsonb)
        (doto (org.postgresql.util.PGobject.)
          (.setType (name as))
          (.setValue (json/generate-string v)))
        :array
        (into-array v))))

(defn deserialize [xs]
  (if (map? xs)
    (if-let [as (:pod.babashka.sql/write xs)]
      (let [v (::val xs)]
        (coerce v as))
      xs)
    xs))

;; Default implementation
(defn serialize-pg-obj [opts x]
      (cond
        #_? (instance? java.sql.Array x)
        #_=> (let [arr (.getArray ^java.sql.Array x)
                   coerce-opt (get-in opts [:pod.babashka.sql/read :array])
                   coerced (case coerce-opt
                             :array {::val (vec arr)
                                     ::read :array}
                             (vec arr))]
               coerced)
        :else #_=> x))

(when-pg
    (defn serialize [opts x]
      (cond
        #_? (instance? org.postgresql.util.PGobject x)
        #_=> (let [t (.getType ^org.postgresql.util.PGobject x)
                   coerce-opts (get opts :pod.babashka.sql/read)
                   coerced (case t
                             ("json" "jsonb")
                             (case (get coerce-opts (keyword t))
                               :parse+keywordize
                               (json/parse-string (.getValue x) true)
                               :parse
                               (json/parse-string (.getValue x))
                               :string
                               (.getValue x)
                               ;; default JSON handler
                               (json/parse-string (.getValue x) true)))]
               coerced)
        #_? (instance? java.sql.Array x)
        #_=> (let [arr (.getArray ^java.sql.Array x)
                   coerce-opt (get-in opts [:pod.babashka.sql/read :array])
                   coerced (case coerce-opt
                             :array {::val (vec arr)
                                     ::read :array}
                             (vec arr))]
               coerced)
        :else #_=> x))
  nil)

(defn -execute!
  ([db-spec sql-params]
   (-execute! db-spec sql-params nil))
  ([db-spec sql-params opts]
   (let [sql-params (walk/postwalk deserialize sql-params)
         conn (->connectable db-spec)
         res (jdbc/execute! conn sql-params opts)]
     (walk/postwalk #(serialize opts %) res))))

(defn -execute-one!
  ([db-spec sql-params]
   (-execute-one! db-spec sql-params nil))
  ([db-spec sql-params opts]
   (let [sql-params (walk/postwalk deserialize sql-params)
         conn (->connectable db-spec)
         res (jdbc/execute-one! conn sql-params opts)]
     (walk/postwalk #(serialize opts %) res))))

(defn -insert-multi!
  ([db-spec table cols rows]
   (-insert-multi! db-spec table cols rows nil))
  ([db-spec table cols rows opts]
   (let [rows (walk/postwalk deserialize rows)
         conn (->connectable db-spec)
         res (sql/insert-multi! conn table cols rows)]
     (walk/postwalk #(serialize opts %) res))))

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

(def sql-sql-ns (cond features/postgresql? "pod.babashka.postgresql.sql"
                      features/hsqldb? "pod.babashka.hsqldb.sql"
                      features/oracle? "pod.babashka.oracle.sql"
                      features/mssql? "pod.babashka.mssql.sql"
                      :else (throw (Exception. "Feature flag expected."))))

(def lookup
  (let [m {'-execute! -execute!
           '-execute-one! -execute-one!
           'get-connection get-connection
           'close-connection close-connection
           'transaction/begin transaction-begin
           'transaction/rollback transaction-rollback
           'transaction/commit transaction-commit
           'sql/-insert-multi! -insert-multi!}]
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

(defn replace-sql-ns [s]
  (-> s
      (str/replace "sqlns" sql-ns)
      (str/replace "sql-sql-ns" sql-sql-ns)))

(def execute-str
  (replace-sql-ns (str '(defn execute! [db-spec & args]
                          (sqlns/-deserialize (apply sqlns/-execute! db-spec (sqlns/-serialize args)))))))

(def execute-one-str
  (replace-sql-ns (str '(defn execute-one! [db-spec & args]
                          (sqlns/-deserialize (apply sqlns/-execute-one! db-spec (sqlns/-serialize args)))))))

(def insert-multi-str
  (-> (str '(defn insert-multi! [db-spec table cols rows]
              (sql-sql-ns/-insert-multi! db-spec table cols (sqlns/-serialize rows))))
      replace-sql-ns))

(def -serialize-1-str
  (pr-str '(defn -serialize-1 [x]
             (if-let [c (class x)]
               (if
                 (.isArray c)
                 {::write :array
                  ::val (vec x)}
                 (let [m (meta x)
                       t (:pod.babashka.sql/write m)]
                   (if t
                     {::write t
                      ::val x}
                     x)))
               x))))

(def -serialize-str
  (replace-sql-ns
   (pr-str '(do (require 'clojure.walk)
                (defn -serialize [obj]
                  (clojure.walk/postwalk sqlns/-serialize-1 obj))))))

(def -deserialize-1-str
  (pr-str '(defn -deserialize-1 [x]
             (if (map? x)
               (if-let [t (::read x)]
                 (let [v (::val x)]
                   (case t
                     :array (into-array x)))
                 x)
               x))))

(def -deserialize-str
  (replace-sql-ns
   (pr-str '(do (require 'clojure.walk)
                (defn -deserialize [obj]
                  (clojure.walk/postwalk sqlns/-deserialize-1 obj))))))

(def describe-map
  (walk/postwalk
   (fn [v]
     (if (ident? v) (name v)
         v))
   `{:format :transit+json
     :namespaces [{:name ~(symbol sql-ns)
                   :vars [{:name -execute!}
                          {:name -execute-one!}
                          {:name -serialize-1
                           :code ~-serialize-1-str}
                          {:name -serialize
                           :code ~-serialize-str}
                          {:name -deserialize-1
                           :code ~-deserialize-1-str}
                          {:name -deserialize
                           :code ~-deserialize-str}
                          {:name execute!
                           :code ~execute-str}
                          {:name execute-one!
                           :code ~execute-one-str}
                          {:name get-connection}
                          {:name close-connection}
                          {:name with-transaction
                           :code ~with-transaction}]}
                  {:name ~(symbol (str sql-ns ".transaction"))
                   :vars [{:name begin}
                          {:name rollback}
                          {:name commit}]}
                  {:name ~(symbol (str sql-ns ".sql"))
                   :vars [{:name -insert-multi!}
                          {:name insert-multi!
                           :code ~insert-multi-str}]}]
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
