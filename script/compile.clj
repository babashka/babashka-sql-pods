#!/usr/bin/env bb

(ns compile
  (:require [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [clojure.string :as str]))

(def graalvm (System/getenv "GRAALVM_HOME"))

(assert graalvm "Set GRAALVM_HOME")

(def pod-db-type (System/getenv "POD_DB_TYPE"))

(assert pod-db-type "Set POD_DB_TYPE")

(def gvm-bin (fs/file graalvm "bin"))

(def windows?
  (str/includes? (str/lower-case (System/getProperty "os.name"))
                 "windows"))

(def path (str/join fs/path-separator [gvm-bin (System/getenv "PATH")]))

(let [lein-profiles "+uberjar"
      refl-conf (str "reflection-" pod-db-type ".json")
      lein-profiles (str lein-profiles "," "+feature/" pod-db-type)
      version (str/trim (slurp "resources/POD_BABASHKA_SQL_VERSION"))]
  (println "Profiles:" lein-profiles)
  (println "Reflection config:" refl-conf)
  (shell "java -version")
  (shell (str "lein" (when windows? ".bat") " with-profiles")
         lein-profiles "do" "clean," "uberjar")
  (let [pod-name (str "pod-babashka-" pod-db-type)
        jar (format "target/pod-babashka-sql-%s-standalone.jar" version)
        xmx (or (System/getenv "BABASHKA_XMX") "-J-Xmx4500m")
        args ["-jar" jar
              (str "-H:Name=" pod-name)
              "-H:+ReportExceptionStackTraces"
              "-J-Dclojure.spec.skip-macros=true"
              "-J-Dclojure.compiler.direct-linking=true"
              (str "-H:IncludeResources=" version)
              (str "-H:ReflectionConfigurationFiles=" refl-conf)
              "-H:Log=registerResource:"
              "-H:EnableURLProtocols=http,https"
              "--enable-all-security-services"
              "--allow-incomplete-classpath"
              "-H:+JNI"
              "--verbose"
              "--no-fallback"
              "--no-server"
              "--report-unsupported-elements-at-runtime"
              "-H:+AddAllCharsets"
              "-H:IncludeResources=org/hsqldb/.*.properties"
              "-H:IncludeResources=org/hsqldb/.*.sql"
              "--initialize-at-build-time=com.cognitect.transit"
              "--initialize-at-build-time=com.fasterxml.jackson"
              "--initialize-at-run-time=com.mysql.cj.jdbc.AbandonedConnectionCleanupThread"
              "--initialize-at-run-time=com.mysql.cj.jdbc.AbandonedConnectionCleanupThread.AbandonedConnectionCleanupThread"
              "--initialize-at-run-time=com.mysql.cj.jdbc.Driver"
              "--initialize-at-run-time=com.mysql.cj.jdbc.NonRegisteringDriver"
              "--initialize-at-run-time=org.postgresql.sspi.SSPIClient"
              "--initialize-at-run-time=com.microsoft.sqlserver.jdbc.SQLServerColumnEncryptionAzureKeyVaultProvider"
              "--initialize-at-run-time=com.microsoft.sqlserver.jdbc.SQLServerFMTQuery"
              "--initialize-at-run-time=com.microsoft.sqlserver.jdbc.SQLServerBouncyCastleLoader"
              "--initialize-at-run-time=com.microsoft.sqlserver.jdbc.SQLServerMSAL4JUtils"
              #_#_"--initialize-at-build-time=oracle.i18n.text.OraCharsetWithConverter"
              "--initialize-at-build-time=oracle.i18n.text.OraCharsetAL16UTF16"
              "--initialize-at-build-time=oracle.i18n.text"
              "--features=clj_easy.graal_build_time.InitClojureClasses"
              "-EPOD_DB_TYPE"
              xmx]
        args (if (= "mssql" pod-db-type)
               (conj args "-H:IncludeResourceBundles=com.microsoft.sqlserver.jdbc.SQLServerResource")
               args)
        args (if (= "true" (System/getenv "BABASHKA_STATIC"))
               (if (= "true" (System/getenv "BABASHKA_MUSL"))
                 (let [args (conj args "--static")]
                   (conj args
                         "--libc=musl"
                         ;; see https://github.com/oracle/graal/issues/3398
                         "-H:CCompilerOption=-Wl,-z,stack-size=2097152"))
                 (-> (conj args "-H:+StaticExecutableWithDynamicLibC")
                     (conj "-H:+UnlockExperimentalVMOptions")))
               args)]
    (apply shell (str (fs/file gvm-bin "native-image")
                      (when windows?
                        ".cmd")) args)))
