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

(set! *warn-on-reflection* true)

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

(defmacro if-mysql [then else]
  (if features/mysql?
    then
    else))

(defmacro when-mysql [& body]
  `(if-mysql (do ~@body) nil))

(defmacro if-pg [then else]
  (if features/postgresql?
    then
    else))

(defmacro when-pg [& body]
  `(if-pg (do ~@body) nil))

;; Default implementation
(defn serialize [_opts x]
  x)

(when-pg
    (defn serialize [opts x]
      (cond
        #_? (instance? org.postgresql.util.PGobject x)
        #_=> (let [^ org.postgresql.util.PGobject x x
                   t (.getType x)
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
        :else #_=> x))
  nil)

(defn -execute!
  ([db-spec sql-params]
   (-execute! db-spec sql-params nil))
  ([db-spec sql-params opts]
   ;; (.println System/err (str sql-params))
   (let [conn (->connectable db-spec)
         res (jdbc/execute! conn sql-params opts)]
     (walk/postwalk #(serialize opts %) res))))

(defn -execute-one!
  ([db-spec sql-params]
   (-execute-one! db-spec sql-params nil))
  ([db-spec sql-params opts]
   (let [conn (->connectable db-spec)
         res (jdbc/execute-one! conn sql-params opts)]
     (walk/postwalk #(serialize opts %) res))))

(defn -insert-multi!
  ([db-spec table cols rows]
   (-insert-multi! db-spec table cols rows nil))
  ([db-spec table cols rows opts]
   (let [conn (->connectable db-spec)
         res (sql/insert-multi! conn table cols rows)]
     (walk/postwalk #(serialize opts %) res))))

(defn close-connection [{:keys [::connection]}]
  (let [[old _new] (swap-vals! conns dissoc connection)]
    (when-let [conn (get old connection)]
      (.close ^java.lang.AutoCloseable conn))))

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
                  features/mysql? "pod.babashka.mysql"
                  features/oracle? "pod.babashka.oracle"
                  features/mssql? "pod.babashka.mssql"
                  :else (throw (Exception. "Feature flag expected."))))

(def sql-sql-ns (cond features/postgresql? "pod.babashka.postgresql.sql"
                      features/hsqldb? "pod.babashka.hsqldb.sql"
                      features/mysql? "pod.babashka.mysql.sql"
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
                          (apply sqlns/-execute! db-spec (sqlns/-serialize args))))))

(def execute-one-str
  (replace-sql-ns (str '(defn execute-one! [db-spec & args]
                          (apply sqlns/-execute-one! db-spec (sqlns/-serialize args))))))

(def insert-multi-str
  (-> (str '(defn insert-multi! [db-spec table cols rows]
              (sql-sql-ns/-insert-multi! db-spec table cols (sqlns/-serialize rows))))
      replace-sql-ns))

(def -serialize-1-str
  (pr-str '(defn -serialize-1 [x]
             (if-let [c (class x)]
               (let [m (meta x)
                     t (:pod.babashka.sql/write m)]
                 (if
                     t
                   {::write t
                    ::val x}
                   x))
               x))))

(def -serialize-str
  (replace-sql-ns
   (pr-str '(do (require 'clojure.walk)
                (defn -serialize [obj]
                  (clojure.walk/postwalk sqlns/-serialize-1 obj))))))

(def ldt-key (str ::local-date-time))
(def jsa-key (str ::java-sql-array))

(def reg-transit-handlers
  (format "
(require 'babashka.pods)
(defmacro when-transit-handlers [& body]
  (when (resolve 'babashka.pods/add-transit-read-handler!)
  `(do ~@body)))

(when-transit-handlers

  (babashka.pods/add-transit-read-handler!
    \"%s\"
    (fn [s] (java.time.LocalDateTime/parse s)))

  (babashka.pods/add-transit-write-handler!
    #{java.time.LocalDateTime}
    \"%s\"
    str))

  (babashka.pods/add-transit-read-handler!
      \"%s\"
      vec)

  (babashka.pods/set-default-transit-write-handler!
    (fn [x] (when (.isArray (class x)) \"java.array\"))
    vec)"
          ldt-key ldt-key
          jsa-key))

(def json-str
  "(defn write-json [obj]
     (cognitect.transit/tagged-value \"json\" obj))
   (defn write-jsonb [obj]
     (cognitect.transit/tagged-value \"jsonb\" obj))")

(def describe-map
  (walk/postwalk
   (fn [v]
     (if (ident? v) (name v)
         v))
   `{:format :transit+json
     :namespaces [{:name ~(symbol sql-ns)
                   :vars [{:name -reg-transit-handlers
                           :code ~reg-transit-handlers}
                          {:name json
                           :code ~json-str}
                          {:name -execute!}
                          {:name -execute-one!}
                          {:name -serialize-1
                           :code ~-serialize-1-str}
                          {:name -serialize
                           :code ~-serialize-str}
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

(def ldt-read-handler (transit/read-handler #(java.time.LocalDateTime/parse %)))
(def java-array-read-handler (transit/read-handler into-array))

(def jak "java.array")

(def jsonk "json")
(def json-read-handler
  (transit/read-handler (fn [obj]
                          (if-pg
                              (doto (org.postgresql.util.PGobject.)
                                (.setType "json")
                                (.setValue (json/generate-string obj)))
                            obj))))

(def jsonbk "jsonb")
(def jsonb-read-handler
  (transit/read-handler (fn [obj]
                          (if-pg
                              (doto (org.postgresql.util.PGobject.)
                                (.setType "jsonb")
                                (.setValue (json/generate-string obj)))
                            obj))))

(def rhm (transit/read-handler-map {ldt-key ldt-read-handler
                                    jak java-array-read-handler
                                    jsonk json-read-handler
                                    jsonbk jsonb-read-handler}))

(defn read-transit [^String v]
  (transit/read
   (transit/reader
    (java.io.ByteArrayInputStream. (.getBytes v "utf-8"))
    :json
    {:handlers rhm})))

(def ldt-write-handler (transit/write-handler ldt-key str))

(def jsa-write-handler (transit/write-handler
                        jsa-key
                        (fn [^java.sql.Array arr]
                          (into-array Object (.getArray arr)))))

(defn write-transit [v]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (transit/write (transit/writer baos :json {:handlers {java.time.LocalDateTime ldt-write-handler
                                                          java.sql.Array jsa-write-handler}}) v)
    (.toString baos "utf-8")))

(def musl?
  "Captured at compile time, to know if we are running inside a
  statically compiled executable with musl."
  (and (= "true" (System/getenv "BABASHKA_STATIC"))
       (= "true" (System/getenv "BABASHKA_MUSL"))))

(defn main []
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

(defmacro run [expr]
  (if musl?
    ;; When running in musl-compiled static executable we lift execution of bb
    ;; inside a thread, so we have a larger than default stack size, set by an
    ;; argument to the linker. See https://github.com/oracle/graal/issues/3398
    `(let [v# (volatile! nil)
           f# (fn []
                (vreset! v# ~expr))]
       (doto (Thread. nil f# "main")
         (.start)
         (.join))
       @v#)
    `(do ~expr)))

(defn -main [& _args]
  (run (main)))
