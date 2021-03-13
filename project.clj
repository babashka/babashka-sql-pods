(defproject babashka/pod-babashka-sql
  #=(clojure.string/trim
     #=(slurp "resources/POD_BABASHKA_SQL_VERSION"))
  :description "babashka pod for HSQLDB"
  :url "https://github.com/borkdude/pod-babashka-hsqldb"
  :scm {:name "git"
        :url "https://github.com/borkdude/pod-babashka-hsqldb"}
  :license {:name "Eclipse Public License 1.0"
            :url "http://opensource.org/licenses/eclipse-1.0.php"}
  :source-paths ["src"]
  :resource-paths ["resources"]
  :dependencies [[org.clojure/clojure "1.10.2"]
                 [seancorfield/next.jdbc "1.1.613"]
                 [nrepl/bencode "1.1.0"]
                 [com.cognitect/transit-clj "1.0.324"]
                 [cheshire/cheshire "5.10.0"]]
  :profiles {:uberjar {:global-vars {*assert* false}
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"
                                  "-Dclojure.spec.skip-macros=true"]
                       :aot :all
                       :main pod.babashka.sql}
             :feature/postgresql {:dependencies [[org.postgresql/postgresql "42.2.18"]]}
             :feature/mssql {:dependencies [[com.microsoft.sqlserver/mssql-jdbc "9.2.0.jre11"]]}
             :feature/hsqldb {:dependencies [[org.hsqldb/hsqldb "2.5.1"]]}
             :feature/oracle {:dependencies [[io.helidon.integrations.db/ojdbc "2.2.0"]]}} ; ojdbc10 + GraalVM config, by Oracle
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_user
                                    :password :env/clojars_pass
                                    :sign-releases false}]])
