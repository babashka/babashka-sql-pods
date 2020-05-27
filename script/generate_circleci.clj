#!/usr/bin/env bb

(ns generate-circleci)

(def linux
  '{:docker ({:image "circleci/clojure:lein-2.8.1"}),
    :working_directory "~/repo",
    :environment {:LEIN_ROOT "true",
                  :GRAALVM_HOME "/home/circleci/graalvm-ce-java8-19.3.1",
                  :BABASHKA_PLATFORM "linux",
                  :BABASHKA_TEST_ENV "native",
                  :BABASHKA_XMX "-J-Xmx7g"},
    :resource_class "large",
    :steps ("checkout"
            {:run {:name "Pull Submodules",
                   :command "git submodule init\ngit submodule update\n"}}
            {:restore_cache {:keys ("linux-{{ checksum \"project.clj\" }}-{{ checksum \".circleci/config.yml\" }}")}}
            {:run {:name "Install Clojure",
                   :command "wget https://download.clojure.org/install/linux-install-1.10.1.447.sh\nchmod +x linux-install-1.10.1.447.sh\nsudo ./linux-install-1.10.1.447.sh\n"}}
            {:run {:name "Install lsof",
                   :command "sudo apt-get install lsof\n"}}
            {:run {:name "Install native dev tools",
                   :command "sudo apt-get update\nsudo apt-get -y install gcc g++ zlib1g-dev\n"}}
            {:run {:name "Download GraalVM",
                   :command "cd ~\nif ! [ -d graalvm-ce-java8-19.3.1 ]; then\n  curl -O -sL https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-19.3.1/graalvm-ce-java8-linux-amd64-19.3.1.tar.gz\n  tar xzf graalvm-ce-java8-linux-amd64-19.3.1.tar.gz\nfi\n"}}
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
                  :BABASHKA_XMX "-J-Xmx7g"},
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
                   :command "cd ~\nls -la\nif ! [ -d graalvm-ce-java8-19.3.1 ]; then\n  curl -O -sL https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-19.3.1/graalvm-ce-java8-darwin-amd64-19.3.1.tar.gz\n  tar xzf graalvm-ce-java8-darwin-amd64-19.3.1.tar.gz\nfi\n"}}
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
   :jobs {:linux linux,
          :mac mac},
   :workflows {:version 2, :ci {:jobs ["linux" "mac"]}}})

(require '[clj-yaml.core :as yaml])
(spit ".circleci/config.yml" (yaml/generate-string config))
