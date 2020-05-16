(ns pod.babashka.hsqldb
  (:refer-clojure :exclude [read read-string])
  (:require [bencode.core :as bencode]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [clojure.walk :as walk])
  (:import [java.io PushbackInputStream])
  (:gen-class))

(def debug? false)
(defn debug [& args]
  (when debug?
    (binding [*out* (io/writer "/tmp/debug.log" :append true)]
      (apply println args))))

(def stdin (PushbackInputStream. System/in))

(defn write [v]
  (bencode/write-bencode System/out v)
  (.flush System/out))

(defn read-string [^"[B" v]
  (String. v))

(defn read []
  (bencode/read-bencode stdin))

(def conns (atom {}))

(defn get-connection [db-spec]
  (let [conn (jdbc/get-connection db-spec)
        conn-id (str (java.util.UUID/randomUUID))]
    (swap! conns assoc conn-id conn)
    {::connection conn-id}))

(defn execute! [db-spec & args]
  (if (map? db-spec)
    (if-let [conn-id (::connection db-spec)]
      (let [connection (get @conns conn-id)]
        (apply jdbc/execute! connection args))
      (apply jdbc/execute! db-spec args))
    (apply jdbc/execute! db-spec args)))

(defn close-connection [{:keys [::connection]}]
  (let [[old _new] (swap-vals! conns dissoc connection)]
    (when-let [conn (get old connection)]
      (.close conn))))

(def lookup
  {'pod.babashka.hsqldb/execute! execute!
   'pod.babashka.hsqldb/get-connection get-connection
   'pod.babashka.hsqldb/close-connection close-connection
   'pod.babashka.hsqldb.sql/insert-multi! sql/insert-multi!})

(def describe-map
  (walk/postwalk
   (fn [v]
     (if (ident? v) (name v)
         v))
   '{:format :edn
     :namespaces [{:name pod.babashka.hsqldb
                   :vars [{:name execute!}
                          {:name get-connection}
                          {:name close-connection}]}
                  {:name pod.babashka.hsqldb.sql
                   :vars [{:name insert-multi!}]}]
     :opts {:shutdown {}}}))

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
                                args (edn/read-string args)]
                            (if-let [f (lookup var)]
                              (let [value (pr-str (apply f args))
                                    reply {"value" value
                                           "id" id
                                           "status" ["done"]}]
                                (write reply))
                              (throw (ex-info (str "Var not found: " var) {}))))
                          (catch Throwable e
                            (binding [*out* *err*]
                              (println e))
                            (let [reply {"ex-message" (ex-message e)
                                         "ex-data" (pr-str
                                                    (assoc (ex-data e)
                                                           :type (class e)))
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
