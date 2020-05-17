(defmacro with-transaction
  [[sym transactable opts] & body]
  `(let [~sym (pod.babashka.hsqldb/get-connection ~transactable)]
     (try
       (pod.babashka.hsqldb.transaction/begin ~sym ~opts)
       (let [res# (do ~@body)]
         (pod.babashka.hsqldb.transaction/commit ~sym)
         res#)
       (catch Exception e#
         (pod.babashka.hsqldb.transaction/rollback ~sym)
         (throw e#)))))
