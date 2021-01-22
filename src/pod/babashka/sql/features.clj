(ns pod.babashka.sql.features
  {:no-doc true})

(def postgresql? (= (System/getenv "POD_DB_TYPE") "postgresql"))
(def hsqldb? (= (System/getenv "POD_DB_TYPE") "hsqldb"))
(def oracle? (= (System/getenv "POD_DB_TYPE") "oracle"))
