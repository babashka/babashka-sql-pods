(ns pod.babashka.hsqldb
  (:refer-clojure :exclude [read read-string])
  (:require [bencode.core :as bencode]
            [clojure.edn :as edn]
            [next.jdbc :as jdbc]
            [clojure.java.io :as io])
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
            :describe (do (write {"format" "edn"
                                  "namespaces" [{"name" "pod.babashka.hsqldb"
                                                 "vars" [{"name" "execute!"}]}]
                                  "id" id})
                          (recur))
            :invoke (do (try
                          (let [var (-> (get message "var")
                                        read-string
                                        symbol)
                                args (get message "args")
                                args (read-string args)
                                args (edn/read-string args)
                                ]
                            (case var
                              pod.babashka.hsqldb/execute!
                              (let [value (pr-str (apply jdbc/execute! args))

                                    reply {"value" value
                                           "id" id
                                           "status" ["done"]}]
                                (write reply))))
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
            (recur)))))))
