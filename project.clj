(defproject babashka/pod-babashka-sql
  #=(clojure.string/trim
     #=(slurp "resources/POD_BABASHKA_SQL_VERSION"))
  :description "babashka pod for SQL databases"
  :url "https://github.com/borkdude/pod-babashka-hsqldb"
  :scm {:name "git"
        :url "https://github.com/borkdude/pod-babashka-hsqldb"}
  :license {:name "Eclipse Public License 1.0"
            :url "http://opensource.org/licenses/eclipse-1.0.php"}
  :source-paths ["src"]
  :resource-paths ["resources"]
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [com.github.seancorfield/next.jdbc "1.3.1093"]
                 [nrepl/bencode "1.1.0"]
                 [com.cognitect/transit-clj "1.0.333"]
                 [cheshire/cheshire "6.1.0"]
                 [com.github.clj-easy/graal-build-time "1.0.5"]]
  :profiles {:uberjar {:global-vars {*assert* false}
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"
                                  "-Dclojure.spec.skip-macros=true"]
                       :aot :all
                       :main pod.babashka.sql}
             :feature/postgresql {:dependencies [[org.postgresql/postgresql "42.7.10"]]}
             :feature/mssql {:dependencies [[com.microsoft.sqlserver/mssql-jdbc "13.2.1.jre11"]]}
             :feature/hsqldb {:dependencies [[org.hsqldb/hsqldb "2.7.4"]]}
             :feature/mysql {:dependencies [[com.mysql/mysql-connector-j "9.6.0"]]}
             :feature/oracle {:dependencies [[io.helidon.integrations.db/ojdbc "4.3.4"]]}
}
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_user
                                    :password :env/clojars_pass
                                    :sign-releases false}]])
