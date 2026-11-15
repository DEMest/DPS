(ns c2.core-test
  (:require [clojure.test :refer [are deftest is testing]]
            [c2.core :refer [primes]]))

(defn- prime? [n]
  (and (> n 1)
       (not-any? #(zero? (rem n %))
                 (range 2 (inc (long (Math/sqrt n)))))))

(deftest first-primes-test
  (is (= [2 3 5 7 11 13 17 19 23 29] (take 10 primes)))
  (is (= 2 (first primes))))

(deftest nth-prime-test
  (is (= 541 (nth primes 99)))
  (is (= 7919 (nth primes 999)))
  (is (= 104729 (nth primes 9999))))

(deftest compare-with-trial-division-test
  (is (= (filter prime? (range 20000))
         (take-while #(< % 20000) primes))))

(deftest count-below-test
  (are [limit cnt] (= cnt (count (take-while #(< % limit) primes)))
    10 4
    100 25
    1000 168
    100000 9592))

(deftest properties-test
  (let [ps (take 5000 primes)]
    (testing "все элементы простые"
      (is (every? prime? ps)))
    (testing "строго возрастают"
      (is (apply < ps)))))

(deftest laziness-test
  (is (instance? clojure.lang.LazySeq primes))
  (is (= 1000003 (first (drop-while #(< % 1000000) primes)))))
