#!/usr/bin/env bb

(ns generate-circleci)

(def linux
  '{:docker ({:image "circleci/clojure:lein-2.8.1"}),
    :working_directory "~/repo",
    :environment {:LEIN_ROOT "true",
                  :GRAALVM_HOME "/home/circleci/graalvm-ce-java8-19.3.1",
                  :BABASHKA_PLATFORM "linux",
                  :BABASHKA_TEST_ENV "native",
                  :BABASHKA_XMX "-J-Xmx7g"
                  :POD_TEST_ENV "native"},
    :resource_class "large",
    :steps ("checkout"
            {:run {:name "Pull Submodules",
                   :command "git submodule init\ngit submodule update\n"}}
            {:restore_cache {:keys ("linux-{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}")}}
            {:run {:name "Install Clojure",
                   :command "
wget https://download.clojure.org/install/linux-install-1.10.1.447.sh
chmod +x linux-install-1.10.1.447.sh
sudo ./linux-install-1.10.1.447.sh"}}
            {:run {:name "Install lsof",
                   :command "sudo apt-get install lsof\n"}}
            {:run {:name "Install native dev tools",
                   :command "sudo apt-get update\nsudo apt-get -y install gcc g++ zlib1g-dev\n"}}
            {:run {:name "Download GraalVM",
                   :command "
cd ~
if ! [ -d graalvm-ce-java8-19.3.1 ]; then
  curl -O -sL https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-19.3.1/graalvm-ce-java8-linux-amd64-19.3.1.tar.gz
  tar xzf graalvm-ce-java8-linux-amd64-19.3.1.tar.gz
fi"}}
            {:run {:name "Build binary",
                   :command "# script/uberjar\nscript/compile\n",
                   :no_output_timeout "30m"}}
            {:run {:name "Run tests",
                   :command "script/test\n"}}
            {:run {:name "Release",
                   :command ".circleci/script/release\n"}}
            {:save_cache {:paths ("~/.m2"
                                  "~/graalvm-ce-java8-19.3.1"),
                          :key "linux-{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}"}}
            {:store_artifacts {:path "/tmp/release",
                               :destination "release"}})})

(def mac
  '{:macos {:xcode "9.0"},
    :environment {:GRAALVM_HOME "/Users/distiller/graalvm-ce-java8-19.3.1/Contents/Home",
                  :BABASHKA_PLATFORM "macos",
                  :BABASHKA_TEST_ENV "native",
                  :BABASHKA_XMX "-J-Xmx7g"
                  :POD_TEST_ENV "native"},
    :resource_class "large",
    :steps ("checkout"
            {:run {:name "Pull Submodules",
                   :command "git submodule init\ngit submodule update\n"}}
            {:restore_cache {:keys ("mac-{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}")}}
            {:run {:name "Install Clojure",
                   :command "script/install-clojure /usr/local\n"}}
            {:run {:name "Install Leiningen",
                   :command "script/install-leiningen\n"}}
            {:run {:name "Download GraalVM",
                   :command "
cd ~
ls -la
if ! [ -d graalvm-ce-java8-19.3.1 ]; then
  curl -O -sL https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-19.3.1/graalvm-ce-java8-darwin-amd64-19.3.1.tar.gz
  tar xzf graalvm-ce-java8-darwin-amd64-19.3.1.tar.gz
fi"}}
            {:run {:name "Build binary",
                   :command "# script/uberjar\nscript/compile\n",
                   :no_output_timeout "30m"}}
            {:run {:name "Run tests",
                   :command "script/test\n"}}
            {:run {:name "Release",
                   :command ".circleci/script/release\n"}}
            {:save_cache {:paths ("~/.m2"
                                  "~/graalvm-ce-java8-19.3.1"),
                          :key "mac-{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}"}}
            {:store_artifacts {:path "/tmp/release",
                               :destination "release"}})})

(def config
  {:version 2.1,
   :jobs {:linux-hsqldb (assoc-in linux [:environment :POD_FEATURE_HSQLDB] "true")
          :mac-hsqldb  (assoc-in mac [:environment :POD_FEATURE_HSQLDB] "true")},
   :workflows {:version 2
               :ci {:jobs ["linux-hsqldb"
                           "mac-hsqldb"]}}})

(require '[clj-yaml.core :as yaml])
(spit ".circleci/config.yml" (yaml/generate-string config))
