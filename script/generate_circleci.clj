#!/usr/bin/env bb

(ns generate-circleci
  (:require [clojure.string :as str]
            [flatland.ordered.map :refer [ordered-map]]))

(def java-default-version 11)

(def graalvm-version "22.0.0.2")

(defn with-graalvm-version [s]
  (str/replace s "{{graalvm-version}}" graalvm-version))

(defn linux [& {:keys [java static arch] :or {java java-default-version
                                              static false
                                              arch "amd64"}}]
  (let [executor (if (= "aarch64" arch)
                   {:machine {:image "ubuntu-2004:202101-01"}}
                   {:docker [{:image "circleci/clojure:openjdk-11-lein-2.9.6-bullseye"}]})
        resource-class (when (= "aarch64" arch)
                         "arm.large")
        config (merge executor
                 (ordered-map :working_directory "~/repo"
                              :environment (cond-> (ordered-map :LEIN_ROOT "true"
                                                                :GRAALVM_HOME (format "/home/circleci/graalvm-ce-java%s-{{graalvm-version}}" java)
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
                                      {:run {:name "Install Clojure",
                                             :command "
wget https://download.clojure.org/install/linux-install-1.11.1.1224.sh
chmod +x linux-install-1.11.1.1224.sh
sudo ./linux-install-1.11.1.1224.sh"}}
                                      {:run {:name "Install lsof",
                                             :command "sudo apt-get install lsof\n"}}
                                      {:run {:name "Install native dev tools",
                                             :command (str/join "\n" ["sudo apt-get update"
                                                                      "sudo apt-get -y install gcc g++ zlib1g-dev make"
                                                                      (when (not= "aarch64" arch)
                                                                        "sudo -E script/setup-musl")])}}
                                      {:run {:name    "Download GraalVM",
                                             :command (format "
cd ~
if ! [ -d graalvm-ce-java%s-{{graalvm-version}} ]; then
  curl -O -sL https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-{{graalvm-version}}/graalvm-ce-java%s-linux-%s-{{graalvm-version}}.tar.gz
  tar xzf graalvm-ce-java%s-linux-%s-{{graalvm-version}}.tar.gz
fi" java java arch java arch)}}
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
  (ordered-map :macos {:xcode "14.0.0"},
               :environment (ordered-map :GRAALVM_HOME (format "/Users/distiller/graalvm-ce-java%s-{{graalvm-version}}/Contents/Home" java),
                                         :MACOSX_DEPLOYMENT_TARGET "10.13" ;; 10.12 is EOL
                                         :BABASHKA_PLATFORM "macos",
                                         :BABASHKA_TEST_ENV "native",
                                         :BABASHKA_XMX "-J-Xmx7g"
                                         :POD_TEST_ENV "native"),
               :resource_class "large",
               :steps ["checkout"
                       {:run {:name "Pull Submodules",
                              :command "git submodule init\ngit submodule update\n"}}
                       {:restore_cache {:keys ["mac-{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}"]}}
                       {:run {:name "Install Clojure",
                              :command "script/install-clojure /usr/local\n"}}
                       {:run {:name "Install Leiningen",
                              :command "script/install-leiningen\n"}}
                       {:run {:name    "Download GraalVM",
                              :command (format "
cd ~
ls -la
if ! [ -d graalvm-ce-java%s-{{graalvm-version}} ]; then
  curl -O -sL https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-{{graalvm-version}}/graalvm-ce-java%s-darwin-amd64-{{graalvm-version}}.tar.gz
  tar xzf graalvm-ce-java%s-darwin-amd64-{{graalvm-version}}.tar.gz
fi" java java java)}}
                       {:run {:name "Install bb"
                              :command "bash <(curl -s https://raw.githubusercontent.com/borkdude/babashka/master/install) --dir $(pwd)"}}
                       {:run {:name "Fix ssl libs for tests"
                              :command "
ln -s /usr/local/opt/openssl/lib/libcrypto.1.0.0.dylib /usr/local/lib/\n
ln -s /usr/local/opt/openssl/lib/libssl.1.0.0.dylib /usr/local/lib/\n
\n
sudo ln -s /usr/lib/libssl.dylib /usr/local/opt/openssl/lib/libssl.1.0.0.dylib\n
sudo ln -s /usr/lib/libcrypto.dylib /usr/local/opt/openssl/lib/libcrypto.1.0.0.dylib\n
ls -la /usr/lib/libssl.dylib\n
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
          :mysql-linux (assoc-in (linux :static true)
                                        [:environment :POD_DB_TYPE] "mysql")
          :mysql-linux-aarch64 (assoc-in (linux :arch "aarch64" :static true)
                                                [:environment :POD_DB_TYPE] "mysql")
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
