(ns quantile-service.integration.query-handler-test
  (:require
    [clojure.test     :refer :all]
    [ring.mock.request :as mock]
    [quantile-service.core :refer [query-handler]]))

(deftest query-handler-missing-params
  (testing "query-handler without params returns 400"
    (let [req  (mock/request :get "/quantile")
          resp (query-handler {:query-params {}})]
      (is (= 400 (:status resp)))
      (is (= {:error "Missing key, q or window"} (:body resp))))))
