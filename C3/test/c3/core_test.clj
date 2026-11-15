(ns c3.core-test
  (:require [clojure.test :refer [are deftest is testing]]
            [c3.core :refer [pfilter]]))

(deftest same-as-filter-test
  (are [pred block-size coll] (= (filter pred coll) (pfilter pred block-size coll))
    even? 10 (range 100)
    even? 7 (range 100)
    odd? 1 (range 50)
    odd? 1000 (range 50)
    even? 16 (vec (range 1000))
    string? 2 [1 "a" :b "c" nil "d"]
    pos? 3 []
    pos? 3 nil))

(deftest default-block-size-test
  (is (= (filter even? (range 10000)) (pfilter even? (range 10000))))
  (is (= [] (pfilter neg? (range 5000)))))

(deftest infinite-test
  (is (= [0 2 4 6 8] (take 5 (pfilter even? 3 (range)))))
  (is (= 99990 (nth (pfilter #(zero? (rem % 10)) 100 (range)) 9999)))
  (is (= 10000 (first (pfilter #(>= % 10000) 10 (range))))))

(defn- counting-seq
  "Натуральные числа; в counter — сколько элементов уже вычислено."
  [counter]
  (letfn [(gen [n] (lazy-seq (swap! counter inc) (cons n (gen (inc n)))))]
    (gen 0)))

(deftest laziness-test
  (let [counter (atom 0)
        cores (.availableProcessors (Runtime/getRuntime))
        result (pfilter even? 10 (counting-seq counter))]
    (testing "пока результат не нужен, вход не читается"
      (is (zero? @counter)))
    (testing "для первого элемента читается ограниченное число блоков"
      (is (= 0 (first result)))
      (is (<= @counter (* 10 (+ cores 2)))))))

(deftest parallel-test
  (let [running (atom 0)
        max-running (atom 0)
        pred (fn [x]
               (swap! max-running max (swap! running inc))
               (Thread/sleep 20)
               (swap! running dec)
               (even? x))]
    (is (= (filter even? (range 40)) (pfilter pred 5 (range 40))))
    (testing "блоки обрабатываются одновременно"
      (is (> @max-running 1)))))
