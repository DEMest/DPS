(ns c3.core
  (:gen-class))

(defn pfilter
  "Параллельный ленивый filter. coll разбивается на блоки по block-size элементов,
  каждый блок фильтруется в своей future. Вперёд запускается столько блоков,
  сколько ядер у процессора; следующий блок запускается, когда забирают текущий."
  ([pred coll] (pfilter pred 1000 coll))
  ([pred block-size coll]
   (let [n (.availableProcessors (Runtime/getRuntime))
         tasks (map (fn [block] (future (doall (filter pred block))))
                    (partition-all block-size coll))
         step (fn step [tasks running]
                (lazy-seq
                 (when-let [s (seq tasks)]
                   (let [running (seq running)]
                     (concat @(first s) (step (rest s) (rest running)))))))]
     (step tasks (drop n tasks)))))

(defn- slow-prime?
  "Нарочно медленная проверка на простоту: деление на все числа от 2 до n-1."
  [n]
  (and (> n 1)
       (not-any? #(zero? (rem n %)) (range 2 n))))

(defn -main
  "Сравнение времени filter и pfilter на конечной и бесконечной последовательности."
  [& _]
  (dotimes [_ 3]
    (count (filter slow-prime? (range 5000)))
    (count (pfilter slow-prime? (range 5000))))

  (println "Простые числа меньше 30000:")
  (print "filter:  ")
  (time (count (filter slow-prime? (range 30000))))
  (print "pfilter: ")
  (time (count (pfilter slow-prime? (range 30000))))

  (println "Первые 3000 простых из бесконечной последовательности (range):")
  (print "filter:  ")
  (time (last (take 3000 (filter slow-prime? (range)))))
  (print "pfilter: ")
  (time (last (take 3000 (pfilter slow-prime? (range)))))

  (shutdown-agents))
