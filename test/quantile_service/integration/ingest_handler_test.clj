(ns quantile-service.integration.ingest-handler-test
  (:require
    [clojure.test     :refer :all]
    [ring.mock.request :as mock]
    [quantile-service.core :refer [ingest-handler]]))

(deftest ingest-handler-returns-ack
  (testing "ingest-handler returns ack=true and timestamp"
    (let [sample {:value     2.71
                  :timestamp 100}
          req    {:json-params sample}
          resp   (ingest-handler req)]
      (is (= 200 (:status resp)))
      (true? (:ack (:body resp)))
      (is (integer? (:ingestedAt (:body resp)))))))
