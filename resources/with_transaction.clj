(defmacro with-transaction
  [[sym transactable opts] & body]
  `(let [~sym (pod.babashka.sql/get-connection ~transactable)]
     (try
       (pod.babashka.sql.transaction/begin ~sym ~opts)
       (let [res# (do ~@body)]
         (pod.babashka.sql.transaction/commit ~sym)
         res#)
       (catch Exception e#
         (pod.babashka.sql.transaction/rollback ~sym)
         (throw e#)))))
