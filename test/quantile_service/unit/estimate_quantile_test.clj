(ns quantile-service.unit.add-sample-test
  (:require
    [clojure.test :refer :all]
    [quantile-service.core :refer [estimate-quantile ->StateData]]))

(deftest estimate-quantile-single-event
  (testing "estimate-quantile returns the only event's value and count 1"
    (let [now    (System/currentTimeMillis)
          state  (->StateData nil [{:value 42 :timestamp now}])
          result (estimate-quantile state 0.5 1)]
      (is (= 42.0 (:estimate result)))
      (is (= 1    (:count    result))))))

(deftest estimate-quantile-no-events
  (testing "estimate-quantile returns NaN and count 0 when no events"
    (let [state  (->StateData nil [])
          result (estimate-quantile state 0.5 1)]
      (is (java.lang.Double/isNaN ^double (:estimate result)))
      (zero? (:count result)))))

(deftest estimate-quantile-extreme-low
  (testing "estimate-quantile returns minimum for q=0.0"
    (let [now    (System/currentTimeMillis)
          events [{:value 1 :timestamp now}
                  {:value 3 :timestamp now}
                  {:value 5 :timestamp now}]
          state  (->StateData nil events)
          result (estimate-quantile state 0.0 1)]
      (is (= 1.0 (:estimate result)))
      (is (= 3   (:count    result))))))

(deftest estimate-quantile-extreme-high
  (testing "estimate-quantile returns maximum for q=1.0"
    (let [now    (System/currentTimeMillis)
          events [{:value 1 :timestamp now}
                  {:value 3 :timestamp now}
                  {:value 5 :timestamp now}]
          state  (->StateData nil events)
          result (estimate-quantile state 1.0 1)]
      (is (= 5.0 (:estimate result)))
      (is (= 3   (:count    result))))))
