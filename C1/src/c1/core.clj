(ns c1.core
  "Задача C1: все строки длины n над алфавитом, в которых нет двух одинаковых
  символов подряд. Решение построено только на map/reduce/remove и операциях над
  списками — без рекурсии, генераторов (for) и функций вроде flatten."
  (:gen-class))

(defn extend-word
  "Все продолжения слова word на один символ алфавита, кроме символа,
  которым word уже заканчивается."
  [alphabet ^String word]
  (map #(str word %)
       (remove #(.endsWith word %) alphabet)))

(defn extend-words
  "Продолжения на один символ всех слов из words, одним списком."
  [alphabet words]
  ;; Не (reduce concat ...): вложенные друг в друга concat на десятках тысяч слов
  ;; переполняют стек при чтении результата, а apply concat склеивает списки за один проход.
  (apply concat (map #(extend-word alphabet %) words)))

(defn words
  "Все строки длины n из символов alphabet (список строк из одного символа),
  в которых нет двух одинаковых символов подряд.
  (words '(\"a\" \"b\" \"c\") 2) => (\"ab\" \"ac\" \"ba\" \"bc\" \"ca\" \"cb\")"
  [alphabet n]
  ;; Начинаем с одного пустого слова и n раз продлеваем все слова на один символ;
  ;; (range n) только отсчитывает шаги.
  (reduce (fn [acc _] (extend-words alphabet acc))
          '("")
          (range n)))

(defn -main
  "Точка входа: lein run [алфавит] [n], например lein run abc 3.
  Алфавит задаётся одной строкой, каждый её символ — отдельная буква.
  По умолчанию — пример из условия: алфавит abc и n = 2."
  [& [alphabet n]]
  (prn (words (map str (or alphabet "abc"))
              (parse-long (or n "2")))))
