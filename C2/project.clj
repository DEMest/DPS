(defproject c2 "0.1.0-SNAPSHOT"
  :description "Задача C2: бесконечная последовательность простых чисел"
  :dependencies [[org.clojure/clojure "1.12.6"]]
  :main ^:skip-aot c2.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
