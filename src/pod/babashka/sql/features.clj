(ns pod.babashka.sql.features
  {:no-doc true})

(def postgresql? (= (System/getenv "POD_FEATURE_POSTGRESQL") "true"))
(def hsqldb? (= (System/getenv "POD_FEATURE_HSQLDB") "true"))

