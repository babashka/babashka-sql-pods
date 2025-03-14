#!/usr/bin/env bb

(ns generate-circleci
  (:require [clojure.string :as str]
            [flatland.ordered.map :refer [ordered-map]]))

(def java-default-version 11)

(def graalvm-version "23")

(defn with-graalvm-version [s]
  (str/replace s "{{graalvm-version}}" graalvm-version))

(def install-clojure
  {:run {:name "Install Clojure",
         :command "
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/posix-install.sh\n
chmod +x posix-install.sh\n
sudo ./posix-install.sh\n"}})

(defn linux [& {:keys [java static arch] :or {java java-default-version
                                              static false
                                              arch "amd64"}}]
  (let [executor (if (= "aarch64" arch)
                   {:machine {:image "ubuntu-2004:2024.05.1"}}
                   {:docker [{:image "circleci/clojure:openjdk-11-lein-2.9.6-bullseye"}]})
        resource-class (when (= "aarch64" arch)
                         "arm.large")
        config (merge executor
                 (ordered-map :working_directory "~/repo"
                              :environment (cond-> (ordered-map :LEIN_ROOT "true"
                                                                :GRAALVM_HOME "/home/circleci/graalvm-{{graalvm-version}}"
                                                                :BABASHKA_PLATFORM "linux"
                                                                :BABASHKA_TEST_ENV "native"
                                                                :BABASHKA_XMX "-J-Xmx7g"
                                                                :POD_TEST_ENV "native")
                                             static (assoc :BABASHKA_STATIC "true"
                                                           :BABASHKA_MUSL (if (= "aarch64" arch) "false" "true"))
                                             (= "aarch64" arch)
                                             (assoc :BABASHKA_ARCH arch))
                              :resource_class "large"
                              :steps ["checkout"
                                      {:run {:name "Pull Submodules",
                                             :command "git submodule init\ngit submodule update\n"}}
                                      {:restore_cache {:keys ["linux-{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}"]}}
                                      install-clojure
                                      {:run {:name "Install lsof",
                                             :command "sudo apt-get install lsof\n"}}
                                      {:run {:name "Install native dev tools",
                                             :command (str/join "\n" ["sudo apt-get update"
                                                                      "sudo apt-get -y install gcc g++ zlib1g-dev make"
                                                                      (when (not= "aarch64" arch)
                                                                        "sudo -E script/setup-musl")])}}
                                      {:run {:name    "Download GraalVM",
                                             :command "script/install-graalvm"}}
                                      {:run {:name "Install bb"
                                             :command "bash <(curl -s https://raw.githubusercontent.com/borkdude/babashka/master/install) --dir $(pwd)"}}
                                      {:run {:name "Build binary",
                                             :command "./bb script/compile.clj",
                                             :no_output_timeout "30m"}}
                                      {:run {:name "Run tests",
                                             :command (if (= "aarch64" arch)
                                                        "echo 'Skipping tests for ARM'"
                                                        "script/test\n")}}
                                      {:run {:name "Release",
                                             :command ".circleci/script/release\n"}}
                                      {:save_cache {:paths ["~/.m2"]
                                                    :key   "linux-{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}"}}
                                      {:store_artifacts {:path "/tmp/release",
                                                         :destination "release"}}]))]
    (if (nil? resource-class)
      config
      (assoc config :resource_class resource-class))))

(defn mac [& {:keys [java] :or {java java-default-version}}]
  (ordered-map :macos {:xcode "13.4.1"},
               :environment (ordered-map :GRAALVM_HOME (format "/Users/distiller/graalvm-{{graalvm-version}}/Contents/Home"),
                                         :MACOSX_DEPLOYMENT_TARGET "10.13" ;; 10.12 is EOL
                                         :BABASHKA_PLATFORM "macos",
                                         :BABASHKA_TEST_ENV "native",
                                         :BABASHKA_XMX "-J-Xmx7g"
                                         :POD_TEST_ENV "native"),
               :steps ["checkout"
                       {:run {:name "Pull Submodules",
                              :command "git submodule init\ngit submodule update\n"}}
                       {:restore_cache {:keys ["mac-{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}"]}}
                       {:run {:name "Install Rosetta"
                              :command "sudo /usr/sbin/softwareupdate --install-rosetta --agree-to-license"}}
                       install-clojure
                       {:run {:name "Install Leiningen",
                              :command "script/install-leiningen\n"}}
                       {:run {:name    "Download GraalVM",
                              :command "script/install-graalvm"}}
                       {:run {:name "Install bb"
                              :command "bash <(curl -s https://raw.githubusercontent.com/borkdude/babashka/master/install) --dir $(pwd)"}}
                       {:run {:name "Fix ssl libs for tests"
                              :command "
# sudo ln -s /usr/local/opt/openssl@3/lib/libssl.1.0.0.dylib /usr/local/opt/openssl/lib/libssl.1.0.0.dylib\n
# sudo ln -s /usr/local/opt/openssl@3/lib/libcrypto.1.0.0.dylib /usr/local/opt/openssl/lib/libssl.1.0.0.dylib\n
"}}
                       {:run {:name "Build binary",
                              :command "./bb script/compile.clj",
                              :no_output_timeout "30m"}}
                       {:run {:name "Run tests",
                              :command "script/test\n"}}
                       {:run {:name "Release",
                              :command ".circleci/script/release\n"}}
                       {:save_cache {:paths ["~/.m2"]
                                     :key   "mac-{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}"}}
                       {:store_artifacts {:path "/tmp/release",
                                          :destination "release"}}]))

(def config
  (ordered-map
   :version 2.1,
   :jobs (ordered-map
          ;; NOTE: hsqldb tests on java11 fail with a weird NullPointerException (1/2021)
          :hsqldb-linux (assoc-in (linux :static true)
                                         [:environment :POD_DB_TYPE] "hsqldb")
          :hsqldb-linux-aarch64 (assoc-in (linux :arch "aarch64" :static true)
                                                 [:environment :POD_DB_TYPE] "hsqldb")
          ;; graalvm isn't available in version 8 anymore for macOS
          :hsqldb-mac  (assoc-in (mac)
                                 [:environment :POD_DB_TYPE] "hsqldb")
          :duckdb-linux (assoc-in (linux :static true)
                                         [:environment :POD_DB_TYPE] "duckdb")
          :duckdb-linux-aarch64 (assoc-in (linux :arch "aarch64" :static true)
                                                 [:environment :POD_DB_TYPE] "duckdb")
          :duckdb-mac  (assoc-in (mac)
                                 [:environment :POD_DB_TYPE] "duckdb")
          :mysql-linux (assoc-in (linux :static true)
                                        [:environment :POD_DB_TYPE] "mysql")
          :mysql-linux-aarch64 (assoc-in (linux :arch "aarch64" :static true)
                                                [:environment :POD_DB_TYPE] "mysql")
          ;; disabled because of libssl issues
          :mysql-mac (assoc-in (mac)
                               [:environment :POD_DB_TYPE] "mysql")
          :postgresql-linux (assoc-in (linux :static true)
                                             [:environment :POD_DB_TYPE] "postgresql")
          :postgresql-linux-aarch64 (assoc-in (linux :arch "aarch64" :static true)
                                                     [:environment :POD_DB_TYPE] "postgresql")
          :postgresql-mac  (assoc-in (mac) [:environment :POD_DB_TYPE] "postgresql")
          :oracle-linux (assoc-in (linux :static true)
                                         [:environment :POD_DB_TYPE] "oracle")
          :oracle-linux-aarch64 (assoc-in (linux :arch "aarch64" :static true)
                                                 [:environment :POD_DB_TYPE] "oracle")
          :oracle-mac (assoc-in (mac) [:environment :POD_DB_TYPE] "oracle")
          :mssql-linux (assoc-in (linux :static true)
                                        [:environment :POD_DB_TYPE] "mssql")
          :mssql-linux-aarch64 (assoc-in (linux :arch "aarch64" :static true)
                                                [:environment :POD_DB_TYPE] "mssql")
          :mssql-mac (assoc-in (mac) [:environment :POD_DB_TYPE] "mssql")),
   :workflows (ordered-map
               :version 2
               :ci {:jobs ["hsqldb-linux"
                           "hsqldb-linux-aarch64"
                           "hsqldb-mac"
                           "duckdb-linux"
                           "duckdb-linux-aarch64"
                           "duckdb-mac"
                           "mysql-linux"
                           "mysql-linux-aarch64"
                           "mysql-mac"
                           "postgresql-linux"
                           "postgresql-linux-aarch64"
                           "postgresql-mac"
                           "oracle-linux"
                           "oracle-linux-aarch64"
                           "oracle-mac"
                           "mssql-linux"
                           "mssql-linux-aarch64"
                           "mssql-mac"]})))

(require '[clj-yaml.core :as yaml])
(spit ".circleci/config.yml"
      (str "# This file is generated by script/generate_circleci.clj. Please do not edit here.\n"
           (with-graalvm-version
             (yaml/generate-string config
                                   :dumper-options {:flow-style :block}))))
