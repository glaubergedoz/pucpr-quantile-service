(ns quantile-service.unit.add-sample-test
  (:require
    [clojure.test :refer :all]
    [quantile-service.core :refer [add-sample->state]]))

(deftest add-sample->state‑initializes‑and‑appends
  (testing "When the initial state is nil, you should create a new StateData and add the event"
    (let [sample    {:value     3.14
                     :timestamp 1000}
          new-state (add-sample->state nil sample)]
      (is (= [sample] (:events new-state))))))

(deftest add-sample-to-existing-state
  (testing "add-sample->state appends new event to existing state"
    (let [initial-sample {:value 1 :timestamp 100}
          initial-state  (add-sample->state nil initial-sample)
          second-sample  {:value 2 :timestamp 200}
          new-state      (add-sample->state initial-state second-sample)]
      (is (= [initial-sample second-sample]
             (:events new-state))))))

(deftest cms-count-increments
  (testing "CountMinSketch in StateData increments for same value"
    (let [sample {:value 5 :timestamp 100}
          state1 (add-sample->state nil sample)
          state2 (add-sample->state state1 sample)
          cms    (:cms state2)]
      (is (= 2 (.estimateCount cms "5"))))))
