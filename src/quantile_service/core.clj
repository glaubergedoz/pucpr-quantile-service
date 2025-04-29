(ns quantile-service.core
  "Quantile aggregation service with REST e gRPC interfaces, com Mount para lifecycle."
  (:gen-class)
  (:require [clojure.spec.alpha    :as s]
            [clojure.core.async     :as async   :refer [chan go-loop >!! <!]]
            [io.pedestal.http       :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [mount.core             :refer [defstate start stop]])
  (:import (com.tdunning.math.stats TDigest)
           (com.clearspring.analytics.stream.frequency CountMinSketch)
           (io.grpc ServerBuilder)
           (io.grpc ServerBuilder Status) 
           (io.grpc.protobuf.services ProtoReflectionService)
           [quantile QuantileServiceGrpc$QuantileServiceImplBase
            Sample QuantileRequest QuantileResponse Ack]))

;; -------------------- Specs --------------------
(s/def ::key       string?)
(s/def ::value     number?)
(s/def ::timestamp (s/and integer? pos?))
(s/def ::sample    (s/keys :req-un [::key ::value ::timestamp]))
(s/def ::q         (s/and number? #(<= 0 % 1)))
(s/def ::windowSec (s/and integer? pos?))
(s/def ::quantile-request (s/keys :req-un [::key ::q ::windowSec]))

;; -------------------- Shared State --------------------
(defstate states
  :start (atom {}))

(def sample-chan (chan 10000))
(defrecord StateData [cms events])

(defn add-sample->state
  "Pure fn: return new StateData with the added sample."
  [state {:keys [value timestamp]}]
  (let [st (or state
               (->StateData
                 (CountMinSketch. 0.001 0.99 1)
                 []))]
    (.add ^CountMinSketch (:cms st) (str value) 1)
    (update st :events conj {:value value :timestamp timestamp})))

;; -------------------- Ingest Loop (Mount-managed) --------------------
(defstate ingest-loop
  :start
  (do
    (println "[mount] Starting ingest loop")
    (async/go-loop []
      (when-let [sample (<! sample-chan)]
        (if (s/valid? ::sample sample)
          (swap! states update (:key sample) add-sample->state sample)
          (println "[ERROR] Invalid sample:" (s/explain-str ::sample sample)))
        (recur)))
    ingest-loop)
  :stop
  (do
    (println "[mount] Stopping ingest loop")
    (async/close! sample-chan)))

;; -------------------- Quantile Logic --------------------
(defn estimate-quantile
  "Calculate estimate and count within the window."
  [^StateData state q windowSec]
  (let [now      (System/currentTimeMillis)
        cutoff   (- now (* windowSec 1000))
        events   (:events state)
        relevant (filter #(>= (:timestamp %) cutoff) events)
        digest   (TDigest/createDigest 100)
        total    (count relevant)]
    (doseq [{:keys [value]} relevant]
      (.add digest value))
    {:estimate (.quantile digest q)
     :count     total}))

;; -------------------- Handlers --------------------
(defn ingest-handler
  [{:keys [json-params]}]
  (try
    (let [{:keys [key value timestamp]} json-params
          v (cond
              (number? value)           value
              (string? value)           (Double/parseDouble (str value))
              :else (throw (IllegalArgumentException.
                             "Field `value` must be a number or numeric string")))
          ts (cond
               (number? timestamp)      (long timestamp)
               (string? timestamp)      (Long/parseLong (str timestamp))
               :else (throw (IllegalArgumentException.
                              "Field `timestamp` must be a number or numeric string")))
          sample {:key       key
                  :value     v
                  :timestamp ts}]
      (>!! sample-chan sample)
      {:status 200
       :body   {:ack        true
                :ingestedAt ts}})
    (catch NumberFormatException e
      (println "[ingest-handler] format error:" (.getMessage e) "payload=" json-params)
      {:status 400
       :body   {:error   "value or timestamp is not a valid number"
                :details (.getMessage e)}})
    (catch IllegalArgumentException e
      (println "[ingest-handler] invalid input:" (.getMessage e) "payload=" json-params)
      {:status 400
       :body   {:error (.getMessage e)}})
    (catch Exception e
      (println "[ingest-handler] unexpected error:" (.getMessage e) "payload=" json-params)
      {:status 500
       :body   {:error   "Internal server error"
                :details (.getMessage e)}})))

(defn query-handler [{:keys [query-params]}]
  (try
    (let [{:keys [key q window]} query-params
          _ (when-not (and key q window)
              (throw (IllegalArgumentException. "Missing key, q or window")))
          q         (Double/parseDouble  q)
          windowSec (Integer/parseInt   window)
          state     (get @states key)]
      (if state
        {:status 200 :body {:estimate (estimate-quantile state q windowSec)}}
        {:status 404 :body {:error "Key not found"}}))
    (catch NumberFormatException _
      {:status 400 :body {:error "q and window must be numbers"}})
    (catch IllegalArgumentException e
      {:status 400 :body {:error (.getMessage e)}})))

;; -------------------- HTTP Server --------------------
(def routes
  (route/expand-routes
    #{["/samples"  :post ingest-handler :route-name :ingest]
      ["/quantile" :get  query-handler  :route-name :query]}))

(def service-map
  (-> {:env                 :prod
       ::http/host         "0.0.0.0"
       ::http/port         8080
       ::http/type         :jetty
       ::http/join?        false
       ::http/routes       routes
       ::http/resource-path "/public"}
      http/default-interceptors
     (update ::http/interceptors
             (fn [interceptors]
               (into [(body-params/body-params)
                      http/json-body]
                     interceptors)))))

(defstate http-server
  :start
  (do
    (println "[mount] Starting HTTP server on port 8080")
    (-> service-map http/create-server http/start))
  :stop
  (do
    (println "[mount] Stopping HTTP server")
    (http/stop http-server)))

;; -------------------- gRPC Server --------------------
(defstate grpc-server
  :start
  (do
    (println "[mount] Starting gRPC server on port 50051")
    (let [service-impl
          (proxy [QuantileServiceGrpc$QuantileServiceImplBase] []

            ;; IngestSample RPC
            (ingestSample [^Sample req ^io.grpc.stub.StreamObserver obs]
              (try
                (let [k           (.getKey   req)
                      v           (.getValue req)
                      server-ts   (System/currentTimeMillis)
                      sample      {:key       k
                                  :value     v
                                  :timestamp server-ts}]
                  (swap! states update k add-sample->state sample)
                  (let [response (-> (Ack/newBuilder)
                                    (.setSuccess true)
                                    .build)]
                    (.onNext     obs response)
                    (.onCompleted obs)))
                (catch Exception e
                  (println "[gRPC ingestSample] error:" (.getMessage e))
                  (.onError obs
                    (.asRuntimeException
                      (.withDescription Status/INTERNAL
                                        "Internal server error")
                      e)))))

            ;; QueryQuantile RPC
            (queryQuantile [^QuantileRequest req ^io.grpc.stub.StreamObserver obs]
              (let [key       (.getKey req)
                    q         (.getQ req)
                    windowSec (.getWindowSec req)
                    state     (get @states key)]
                (if state
                  (let [events      (:events state)
                        now         (System/currentTimeMillis)
                        cutoff      (- now (* windowSec 1000))
                        relevant    (filter #(>= (:timestamp %) cutoff) events)
                        total-all   (count events)
                        total-rel   (count relevant)]
                    (let [{:keys [estimate count]} (estimate-quantile state q windowSec)
                          response                  (-> (QuantileResponse/newBuilder)
                                                        (.setEstimate estimate)
                                                        (.setCount     count)
                                                        .build)]
                      (.onNext obs response)
                      (.onCompleted obs)))
                  (.onError obs
                    (.asRuntimeException
                      (.withDescription Status/NOT_FOUND
                                        "Key not found")))))))]

      (doto (-> (ServerBuilder/forPort 50051)
                (.addService service-impl)
                (.addService (ProtoReflectionService/newInstance))
                .build)
        .start)))
  :stop
  (do
    (println "[mount] Stopping gRPC server")
    (doto grpc-server
      .shutdown
      .awaitTermination)))

;; -------------------- Main --------------------
(defn -main [& _]
  (start)
  (println "Quantile service running on HTTP:8080 and gRPC:50051"))
