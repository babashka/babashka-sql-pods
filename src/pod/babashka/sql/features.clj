(ns pod.babashka.sql.features
  {:no-doc true})

(def postgresql? (= (System/getenv "POD_DB_NAME") "postgresql"))
(def hsqldb? (= (System/getenv "POD_DB_NAME") "hsqldb"))
