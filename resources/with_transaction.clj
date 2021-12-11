(defmacro with-transaction
  [[sym transactable opts] & body]
  `(let [~sym (pod.babashka.sql/get-connection ~transactable)]
     (prn :with-transact-sym ~sym)
     (try
       (prn :with-transact-begin)
       (pod.babashka.sql.transaction/begin ~sym ~opts)
       (prn :with-transact-exec '~body)
       (let [res# (do ~@body)]
         (prn :with-transact-commit)
         (pod.babashka.sql.transaction/commit ~sym)
         (prn :with-transact-commit-done)
         res#)
       (catch Exception e#
         (pod.babashka.sql.transaction/rollback ~sym)
         (throw e#)))))
