(ns user
  "Helpers for REPL-driven development"
  (:require
    [mount.core                   :as mount]
    [clojure.tools.namespace.repl :refer [refresh]]
    [quantile-service.core]
    ))

(defn stop! []  (mount/stop))
(defn start! [] (mount/start))
(defn restart! []
  (stop!)
  (refresh :after 'user/start!))
