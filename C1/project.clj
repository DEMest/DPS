;; Описание проекта для Leiningen — то же, что build.gradle для Gradle.
(defproject c1 "0.1.0-SNAPSHOT"
  :description "Задача C1"
  ;; Сам Clojure — обычная библиотека из Maven Central, ставить его отдельно не нужно.
  :dependencies [[org.clojure/clojure "1.12.6"]]
  ;; Пространство имён с функцией -main: её вызывает lein run.
  :main ^:skip-aot c1.core
  :target-path "target/%s"
  ;; lein uberjar: заранее компилирует всё в байткод и кладёт вместе с Clojure в один jar.
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
