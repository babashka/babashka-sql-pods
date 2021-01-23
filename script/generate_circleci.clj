#!/usr/bin/env bb

(ns generate-circleci
  (:require [flatland.ordered.map :refer [ordered-map]]))

(def java-default-version 11)

(defn linux [& {:keys [java] :or {java java-default-version}}]
  (ordered-map :docker [{:image "circleci/clojure:lein-2.8.1"}]
               :working_directory "~/repo"
               :environment (ordered-map :LEIN_ROOT "true"
                                         :GRAALVM_HOME (format "/home/circleci/graalvm-ce-java%s-20.3.0" java)
                                         :BABASHKA_PLATFORM "linux"
                                         :BABASHKA_TEST_ENV "native"
                                         :BABASHKA_XMX "-J-Xmx7g"
                                         :POD_TEST_ENV "native")
               :resource_class "large"
               :steps ["checkout"
                       {:run {:name "Pull Submodules",
                              :command "git submodule init\ngit submodule update\n"}}
                       {:restore_cache {:keys ["linux-{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}"]}}
                       {:run {:name "Install Clojure",
                              :command "
wget https://download.clojure.org/install/linux-install-1.10.1.447.sh
chmod +x linux-install-1.10.1.447.sh
sudo ./linux-install-1.10.1.447.sh"}}
                       {:run {:name "Install lsof",
                              :command "sudo apt-get install lsof\n"}}
                       {:run {:name "Install native dev tools",
                              :command "sudo apt-get update\nsudo apt-get -y install gcc g++ zlib1g-dev\n"}}
                       {:run {:name    "Download GraalVM",
                              :command (format "
cd ~
if ! [ -d graalvm-ce-java%s-20.3.0 ]; then
  curl -O -sL https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-20.3.0/graalvm-ce-java%s-linux-amd64-20.3.0.tar.gz
  tar xzf graalvm-ce-java%s-linux-amd64-20.3.0.tar.gz
fi" java java java)}}
                       {:run {:name "Build binary",
                              :command "# script/uberjar\nscript/compile\n",
                              :no_output_timeout "30m"}}
                       {:run {:name "Run tests",
                              :command "script/test\n"}}
                       {:run {:name "Release",
                              :command ".circleci/script/release\n"}}
                       {:save_cache {:paths ["~/.m2"
                                             (format "~/graalvm-ce-java%s-20.3.0" java)],
                                     :key   "linux-{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}"}}
                       {:store_artifacts {:path "/tmp/release",
                                          :destination "release"}}]))

(defn mac [& {:keys [java] :or {java java-default-version}}]
  (ordered-map :macos {:xcode "12.0.0"},
               :environment (ordered-map :GRAALVM_HOME (format "/Users/distiller/graalvm-ce-java%s-20.3.0/Contents/Home" java),
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
if ! [ -d graalvm-ce-java%s-20.3.0 ]; then
  curl -O -sL https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-20.3.0/graalvm-ce-java%s-darwin-amd64-20.3.0.tar.gz
  tar xzf graalvm-ce-java%s-darwin-amd64-20.3.0.tar.gz
fi" java java java)}}
                       {:run {:name "Build binary",
                              :command "# script/uberjar\nscript/compile\n",
                              :no_output_timeout "30m"}}
                       {:run {:name "Run tests",
                              :command "script/test\n"}}
                       {:run {:name "Release",
                              :command ".circleci/script/release\n"}}
                       {:save_cache {:paths ["~/.m2"
                                             (format "~/graalvm-ce-java%s-20.3.0" java)],
                                     :key   "mac-{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}"}}
                       {:store_artifacts {:path "/tmp/release",
                                          :destination "release"}}]))

(def config
  (ordered-map
   :version 2.1,
   :jobs (ordered-map
           ;; NOTE: hsqldb tests on java11 fail with a weird NullPointerException (1/2021)
          :hsqldb-linux (assoc-in (linux :java 8)
                                  [:environment :POD_DB_TYPE] "hsqldb")
          :hsqldb-mac  (assoc-in (mac :java 8)
                                 [:environment :POD_DB_TYPE] "hsqldb")
          :postgresql-linux (assoc-in (linux) [:environment :POD_DB_TYPE] "postgresql")
          :postgresql-mac  (assoc-in (mac) [:environment :POD_DB_TYPE] "postgresql")
          :oracle-linux (assoc-in (linux) [:environment :POD_DB_TYPE] "oracle")
          :oracle-mac (assoc-in (mac) [:environment :POD_DB_TYPE] "oracle")),
   :workflows (ordered-map
               :version 2
               :ci {:jobs ["hsqldb-linux"
                           "hsqldb-mac"
                           "postgresql-linux"
                           "postgresql-mac"
                           "oracle-linux"
                           "oracle-mac"]})))

(require '[clj-yaml.core :as yaml])
(spit ".circleci/config.yml"
      (str "# This file is generated by script/generate_circleci.clj. Please do not edit here.\n"
           (yaml/generate-string config)))
