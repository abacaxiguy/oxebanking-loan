(ns clojure-api-example.core
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :refer [response content-type]]
            [cheshire.core :as json]
            [compojure.core :refer [defroutes GET]]
            [compojure.route :as route])
  (:gen-class))

(defroutes app-routes
  (GET "/hello" []
    (-> (response (json/generate-string {:message "Hello, World!"}))
        (content-type "application/json")))
  (route/not-found "Not Found"))

(defn -main []
  (run-jetty app-routes {:port 3000 :join? false}))


