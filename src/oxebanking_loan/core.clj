(ns oxebanking-loan.core
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :refer [response content-type status]]
            [cheshire.core :as json]
            [compojure.core :refer [defroutes GET POST PUT DELETE]]
            [compojure.route :as route]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql])
  (:gen-class))

(def db-spec {:dbtype "postgresql"
              :dbname "clojure_testing"
              :user "postgres"
              :password "root"
              :host "localhost"
              :port 5432})

(def datasource (jdbc/get-datasource db-spec))

(defn json-response [data & [status-code]]
  (-> (response (json/generate-string data))
      (content-type "application/json")
      (status (or status-code 200))))

;; CRUD operations

(defn create-user [user-data]
  (sql/insert! datasource :users user-data))

(defn get-users []
  (sql/query datasource ["SELECT * FROM users"]))

(defn get-user [id]
  (sql/get-by-id datasource :users id))

(defn update-user [id user-data]
  (sql/update! datasource :users user-data {:id id}))

(defn delete-user [id]
  (sql/delete! datasource :users {:id id}))

(defroutes app-routes
  (GET "/users" []
    (let [users (get-users)]
      (json-response {:users users})))

  (GET "/users/:id" [id]
    (let [user (get-user (Integer/parseInt id))]
      (if user
        (json-response user)
        (json-response {:error "User not found"} 404))))

  (POST "/users" {body :body}
    (let [user-data (json/parse-string (slurp body) true)
          result (create-user user-data)]
      (json-response {:result result} 201)))

  (PUT "/users/:id" {body :body params :params}
    (let [user-id (Integer/parseInt (:id params))
          user-data (json/parse-string (slurp body) true)]
      (update-user user-id user-data)
      (json-response {:message "User updated"})))

  (DELETE "/users/:id" [id]
    (delete-user (Integer/parseInt id))
    (json-response {:message "User deleted"})))

(defn -main []
  (run-jetty app-routes {:port 3000 :join? false}))
