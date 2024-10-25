(ns oxebanking-loan.core
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :refer [response content-type status]]
            [cheshire.core :as json]
            [compojure.core :refer [defroutes GET POST PUT DELETE]]
            [compojure.route :as route]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [clojure.java.io :as io]
            [clojure.core.async :as async :refer [go timeout <!]])
  (:gen-class))

(def db-spec {:dbtype "postgresql"
              :dbname "oxebanking"
              :user "postgres"
              :password "root"
              :host "localhost"
              :port 5432})

(def datasource (jdbc/get-datasource db-spec))

(defn json-response [data & [status-code]]
  (-> (response (json/generate-string data))
      (content-type "application/json")
      (status (or status-code 200))))


(defn create-loan-request [loan-data]
  (sql/insert! datasource :loanRequests loan-data))

(defn get-loan-requests []
  (sql/query datasource ["SELECT * FROM loanRequests"]))

(defn get-loan-request [id]
  (sql/get-by-id datasource :loanRequests id))

(defn update-loan-request [id loan-data]
  (sql/update! datasource :loanRequests loan-data {:id id}))

(defn delete-loan-request [id]
  (sql/delete! datasource :loanRequests {:id id}))


(defn create-loan [loan-data]
  (sql/insert! datasource :loans loan-data))

(defn get-loans []
  (sql/query datasource ["SELECT * FROM loans"]))

(defn get-loan [id]
  (sql/get-by-id datasource :loans id))


(defn create-loan-payment [payment-data]
  (sql/insert! datasource :loanPayments payment-data))

(defn get-loan-payments [loan-id]
  (sql/query datasource ["SELECT * FROM loanPayments WHERE loanId = ?" loan-id]))

;; Helper function to randomize the approval time (max 10 minutes)
(defn random-delay []
  (rand-int (* 10 60 1000))) ;; max 10 min in milliseconds

;; Background task to mimic loan approval
(defn approve-loan [loan-request]
  (future
    (Thread/sleep (random-delay)) ;; Simulate the approval delay
    (let [loan {:customerId (:customerId loan-request)
                :amount (:amount loan-request)
                :status "approved"}]
      (create-loan loan)
      (println "Loan approved and created:" loan))))

(defroutes app-routes
  (GET "/" []
    (json-response {:message "Welcome to the Oxebanking Loan API"}))

  ;; Loan Requests
  (POST "/loans/request" {body :body}
    (let [loan-data (json/parse-string (slurp body) true)
          result (create-loan-request loan-data)]
      (approve-loan loan-data) ;; Start background loan approval process
      (json-response {:result result :message "Loan request received. Processing approval."} 201)))

  (GET "/loans/request" []
    (let [requests (get-loan-requests)]
      (json-response {:requests requests})))

  (GET "/loans/request/:id" [id]
    (let [request (get-loan-request (Integer/parseInt id))]
      (if request
        (json-response request)
        (json-response {:error "Loan request not found"} 404))))

  (PUT "/loans/request/:id" {body :body params :params}
    (let [request-id (Integer/parseInt (:id params))
          loan-data (json/parse-string (slurp body) true)]
      (update-loan-request request-id loan-data)
      (json-response {:message "Loan request updated"})))

  (DELETE "/loans/request/:id" [id]
    (delete-loan-request (Integer/parseInt id))
    (json-response {:message "Loan request deleted"}))

  ;; Loans
  (GET "/loans" []
    (let [loans (get-loans)]
      (json-response {:loans loans})))

  (GET "/loans/:id" [id]
    (let [loan (get-loan (Integer/parseInt id))]
      (if loan
        (json-response loan)
        (json-response {:error "Loan not found"} 404))))

  ;; Loan Payments
  (POST "/loans/:id/payments" {body :body params :params}
    (let [payment-data (json/parse-string (slurp body) true)
          loan-id (Integer/parseInt (:id params))
          result (create-loan-payment (assoc payment-data :loanId loan-id))]
      (json-response {:result result} 201)))

  (GET "/loans/:id/payments" [id]
    (let [payments (get-loan-payments (Integer/parseInt id))]
      (json-response {:payments payments})))


  (defn -main []
    (run-jetty app-routes {:port 3000 :join? false})))
