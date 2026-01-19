(ns demo.app
  (:require [reitit.ring :as rr]
            [ascolais.sandestin :as s]
            [ascolais.twk :as twk]
            [ascolais.sfere :as sfere]
            [ascolais.kaiin :as kaiin]
            [demo.registry :refer [lobby-registry]]
            [demo.handlers :as handlers]
            [demo.system :as system]
            [org.httpkit.server :as hk]
            [starfederation.datastar.clojure.adapter.http-kit :as ds-hk]))

;; Custom routes (connection establishment + complex handlers)
(def custom-routes
  [["/" {:name ::index :get handlers/index}]
   ["/join" {:name ::join :post handlers/join}]
   ["/sse" {:name ::sse :post handlers/sse-connect}]
   ["/message" {:name ::message :post handlers/send-message}]
   ["/leave" {:name ::leave :post handlers/leave}]])

(defn create-dispatch
  "Create sandestin dispatch with all registries."
  [store]
  (s/create-dispatch
   [(twk/registry)
    (sfere/registry store)
    lobby-registry]))

(defn create-router
  "Create the full reitit router combining custom and kaiin routes."
  [dispatch]
  (rr/router
   (into custom-routes (kaiin/routes dispatch))
   {:data {:middleware [(twk/with-datastar ds-hk/->sse-response dispatch)]}}))

(defn wrap-request-logging
  "Middleware that logs all incoming requests."
  [handler]
  (fn [request]
    (tap> {:middleware :request-logging
           :uri (:uri request)
           :method (:request-method request)
           :signals (:signals request)
           :content-type (get-in request [:headers "content-type"])})
    (let [response (handler request)]
      (tap> {:middleware :response-logging
             :uri (:uri request)
             :response (if (map? response)
                         response
                         {:type (type response)})})
      response)))

(defn create-app
  "Create the ring handler with all middleware."
  [store dispatch]
  (rr/ring-handler
   (create-router dispatch)
   (rr/create-default-handler)
   {:middleware [wrap-request-logging]}))

(defn start-system
  "Start the demo system on the given port."
  ([] (start-system 3000))
  ([port]
   (let [store (sfere/store {:type :atom})
         dispatch (create-dispatch store)
         handler (create-app store dispatch)
         server (hk/run-server handler {:port port})]
     (alter-var-root #'system/*system*
                     (constantly {:store store
                                  :dispatch dispatch
                                  :server server}))
     (println (str "Demo running at http://localhost:" port))
     system/*system*)))

(defn stop-system
  "Stop the demo system."
  []
  (when-let [server (:server system/*system*)]
    (server))
  (alter-var-root #'system/*system* (constantly nil))
  (println "Demo stopped"))

(defn -main
  "Entry point for running the demo."
  [& args]
  (let [port (if (seq args)
               (parse-long (first args))
               3000)]
    (start-system port)
    @(promise)))
