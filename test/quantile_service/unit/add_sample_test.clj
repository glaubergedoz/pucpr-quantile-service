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
