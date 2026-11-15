(ns c1.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [c1.core :as core]))

(defn- no-equal-neighbours? [word]
  (not-any? (fn [[a b]] (= a b)) (partition 2 1 word)))

(defn- brute-force
  "Эталон для сравнения: перебрать все k^n строк и отбросить те, где есть
  одинаковые соседние символы. Ограничения задачи на тесты не распространяются."
  [alphabet n]
  (filter no-equal-neighbours?
          (reduce (fn [acc _] (for [word acc, letter alphabet] (str word letter)))
                  [""]
                  (range n))))

(deftest example-test
  (testing "пример из условия, с точностью до перестановки"
    (let [result (core/words '("a" "b" "c") 2)]
      (is (= #{"ab" "ac" "ba" "bc" "ca" "cb"} (set result)))
      (is (= 6 (count result))))))

(deftest edge-cases-test
  (is (= '("") (core/words '("a" "b") 0)) "длина 0 — одно пустое слово")
  (is (= '("a" "b" "c") (core/words '("a" "b" "c") 1)))
  (is (= '("a") (core/words '("a") 1)))
  (is (empty? (core/words '("a") 2)) "из одной буквы слово длиннее 1 не составить")
  (is (empty? (core/words '() 3)) "пустой алфавит"))

(deftest brute-force-test
  (are [alphabet n] (let [result (core/words alphabet n)]
                      (and (= (set (brute-force alphabet n)) (set result))
                           (= (count (brute-force alphabet n)) (count result))))
    '("a" "b") 4
    '("a" "b" "c") 3
    '("a" "b" "c") 6
    '("x" "y" "z" "w") 5
    '("0" "1" "2" "3" "4") 4))

(deftest count-test
  ;; Первая буква — любая из k, каждая следующая — любая из k-1, кроме предыдущей.
  (are [k n] (= (* k (long (Math/pow (dec k) (dec n))))
                (count (core/words (take k '("a" "b" "c" "d" "e")) n)))
    2 7
    3 8
    4 6
    5 5))

(deftest large-test
  (testing "много слов: 3 * 2^14 = 49152"
    (is (= 49152 (count (core/words '("a" "b" "c") 15)))))
  (testing "длинные слова: из двух букв их всегда два, чередующихся"
    (is (= #{(apply str (repeat 500 "ab")) (apply str (repeat 500 "ba"))}
           (set (core/words '("a" "b") 1000))))))

(deftest main-test
  ;; println на Windows заканчивает строку \r\n, поэтому перевод строки обрезается.
  (testing "без аргументов — пример из условия"
    (is (= "(\"ab\" \"ac\" \"ba\" \"bc\" \"ca\" \"cb\")"
           (str/trim (with-out-str (core/-main))))))
  (testing "алфавит строкой и длина"
    (is (= "(\"xyx\" \"yxy\")"
           (str/trim (with-out-str (core/-main "xy" "3")))))))
