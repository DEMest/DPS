(ns task-c4-test
  (:require [clojure.test :refer [deftest is testing]]
            [task-c4 :refer [storage factory notify-msg]]))

(defn- new-factory [& ware-amounts]
  (apply factory 1 10 (storage "Test" 0) ware-amounts))

(deftest takes-only-needed-test
  (let [f (new-factory "A" 2 "B" 3)
        a (atom 5)
        state (notify-msg @(f :worker) "A" a 5)]
    (is (= {"A" 2 "B" 0} (state :buffer)))
    (is (= 3 @a))))

(deftest takes-partially-test
  (testing "на складе меньше, чем нужно, — забирается сколько есть"
    (let [f (new-factory "A" 2 "B" 3)
          b (atom 2)
          state (notify-msg @(f :worker) "B" b 2)]
      (is (= {"A" 0 "B" 2} (state :buffer)))
      (is (= 0 @b)))))

(deftest nothing-to-take-test
  (let [f (new-factory "A" 2)]
    (testing "пустой склад"
      (is (= {"A" 0} ((notify-msg @(f :worker) "A" (atom 0) 0) :buffer))))
    (testing "ресурс не нужен фабрике"
      (let [c (atom 4)]
        (is (= {"A" 0} ((notify-msg @(f :worker) "C" c 4) :buffer)))
        (is (= 4 @c))))))

(deftest failed-retrieval-test
  (testing "валидатор отклонил изъятие — буфер и склад не меняются"
    (let [f (new-factory "A" 2)
          a (atom 3 :validator #(>= % 2))
          state (notify-msg @(f :worker) "A" a 3)]
      (is (= {"A" 0} (state :buffer)))
      (is (= 3 @a)))))

(deftest production-test
  (let [target (storage "Product" 0)
        f (factory 2 10 target "A" 1 "B" 2)
        a (atom 1)
        b (atom 5)]
    (send (f :worker) notify-msg "A" a 1)
    (send (f :worker) notify-msg "B" b 5)
    (await (f :worker))
    (await (target :worker))
    (is (= {"A" 0 "B" 0} (@(f :worker) :buffer)))
    (is (= 2 @(target :storage)))
    (is (= [0 3] [@a @b]))))
