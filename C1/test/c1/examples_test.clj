(ns c1.examples-test
  (:require [clojure.test :refer [are deftest is testing]]
            [c1.examples :as ex]))

(deftest factorial-test
  (are [n expected] (= expected (ex/factorial n))
    0 1
    1 1
    5 120
    20 2432902008176640000)
  (testing "больше Long/MAX_VALUE — BigInt, а не переполнение"
    (is (= 15511210043330985984000000N (ex/factorial 25))))
  (testing "глубокая рекурсия не переполняет стек"
    (is (= 35660 (count (str (ex/factorial 10000)))))))

(deftest gcd-test
  (are [a b expected] (= expected (ex/gcd a b))
    48 18 6
    17 5 1
    7 0 7))

(deftest fibonacci-test
  (is (= [0 1 1 2 3 5 8 13 21 34] (take 10 (ex/fibonacci))))
  (is (= 354224848179261915075N (nth (ex/fibonacci) 100))))

(deftest primes-test
  (is (= [2 3 5 7 11 13 17 19 23 29] (take 10 (ex/primes))))
  (is (not (ex/prime? 1)))
  (is (ex/prime? 7919))
  (is (not (ex/prime? 7917))))

(deftest word-frequencies-test
  (is (= [["be" 2] ["to" 2] ["not" 1] ["or" 1]]
         (ex/word-frequencies "To be, or not to be")))
  (testing "кириллица и знаки препинания"
    (is (= [["мир" 2] ["привет" 1]]
           (ex/word-frequencies "Привет, мир! Мир..."))))
  (is (empty? (ex/word-frequencies "  ,!  "))))

(deftest average-test
  (is (= 2 (ex/average [1 2 3])))
  (is (= 3/2 (ex/average [1 2]))))

(deftest group-averages-test
  (is (= {1 85, 2 70} (ex/group-averages ex/students))))
