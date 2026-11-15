(defproject c4 "0.1.0-SNAPSHOT"
  :description "Задача C4: производственная линия на агентах и атомах"
  :dependencies [[org.clojure/clojure "1.12.6"]]
  :main ^:skip-aot task-c4
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
