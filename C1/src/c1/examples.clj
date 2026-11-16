(ns c1.examples
  "Учебные примеры: как типичные задачи решаются на Clojure.
  Функции можно вызывать в REPL, тесты к ним — в test/c1/examples_test.clj.
  Когда появится решение задачи, этот файл вместе с тестами можно удалить."
  (:require [clojure.string :as str]))

;; Рекурсия. В JVM нет оптимизации хвостовых вызовов, поэтому циклы пишут через
;; loop/recur: recur перепрыгивает в начало loop (или функции), и стек не растёт.

(defn factorial
  "n! через loop/recur. *' при переполнении long переходит на BigInt."
  [n]
  (loop [i n
         acc 1]
    (if (<= i 1)
      acc
      (recur (dec i) (*' acc i)))))

(defn gcd
  "Наибольший общий делитель по Евклиду. recur без loop возвращает в начало функции."
  [a b]
  (if (zero? b)
    a
    (recur b (mod a b))))

;; Ленивые последовательности: элементы вычисляются, только когда их запрашивают,
;; поэтому последовательность может быть бесконечной.

(defn fibonacci
  "Бесконечная последовательность чисел Фибоначчи: (take 10 (fibonacci))."
  []
  (map first (iterate (fn [[a b]] [b (+' a b)]) [0 1])))

(defn prime?
  "Простое ли n: нет делителей от 2 до корня из n."
  [n]
  (and (> n 1)
       (not-any? #(zero? (mod n %))
                 (range 2 (inc (long (Math/sqrt n)))))))

(defn primes
  "Бесконечная последовательность простых чисел."
  []
  (filter prime? (iterate inc 2)))

;; Обработка данных цепочкой map / filter / reduce. Макрос ->> передаёт результат
;; каждого шага последним аргументом в следующий — читается сверху вниз, как конвейер.

(defn word-frequencies
  "Частоты слов в тексте без учёта регистра: [[слово количество] ...],
  самые частые первыми, при равенстве — по алфавиту."
  [text]
  (->> (str/split (str/lower-case text) #"[^\p{L}\p{N}]+")
       (remove str/blank?)
       frequencies
       (sort-by (fn [[word n]] [(- n) word]))))

;; Данные вместо классов: запись — это обычный словарь, набор записей — вектор словарей.

(def students
  [{:name "Ann" :group 1 :score 90}
   {:name "Bob" :group 2 :score 75}
   {:name "Eve" :group 1 :score 80}
   {:name "Max" :group 2 :score 65}])

(defn average
  "Среднее арифметическое. Деление целых даёт точную дробь: (average [1 2]) => 3/2."
  [xs]
  (/ (reduce + xs) (count xs)))

(defn group-averages
  "Средний балл по группам: {номер-группы средний-балл}."
  [students]
  (update-vals (group-by :group students)
               (fn [group] (average (map :score group)))))

(comment
  ;; Блок comment не выполняется при загрузке файла. Сюда удобно складывать выражения
  ;; для REPL: ставим курсор на форму, вычисляем, смотрим результат.
  (factorial 25)
  (gcd 48 18)
  (take 10 (fibonacci))
  (take 10 (primes))
  (word-frequencies "To be, or not to be")
  (group-by :group students)
  (group-averages students)
  )
