(ns c2.core
  (:gen-class))

(defn- sieve
  "Простые числа начиная с n. composites — таблица вычеркнутых составных чисел:
  ключ — ближайшее ещё не пройденное кратное, значение — простые, которые его вычеркнули."
  [n composites]
  (if-let [factors (composites n)]
    (recur (inc n)
           (reduce (fn [table p] (update table (+ n p) conj p))
                   (dissoc composites n)
                   factors))
    (lazy-seq
     (cons n (sieve (inc n) (assoc composites (* n n) [n]))))))

(def primes
  "Бесконечная ленивая последовательность простых чисел."
  (sieve 2 {}))

(defn -main
  "lein run [n] — напечатать первые n простых чисел, по умолчанию 20."
  [& [n]]
  (prn (take (parse-long (or n "20")) primes)))
