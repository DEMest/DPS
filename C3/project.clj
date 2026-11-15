(defproject c3 "0.1.0-SNAPSHOT"
  :description "Задача C3: параллельный filter на futures"
  :dependencies [[org.clojure/clojure "1.12.6"]]
  :main ^:skip-aot c3.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
