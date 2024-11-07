(ns oxebanking-loan.core
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [ring.util.response :refer [response content-type status]]
            [cheshire.core :as json]
            [compojure.core :refer [defroutes GET POST PUT DELETE]]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [clojure.java.io :as io]
            [clojure.core.async :as async :refer [go timeout <!]])
  (:gen-class))

(def db-spec {:dbtype "postgresql"
              :dbname "oxebanking"
              :user "postgres"
              :password "root"
              :host "db"
              :port 5432})

(def datasource (jdbc/get-datasource db-spec))

(defn json-response [data & [status-code]]
  (-> (response (json/generate-string data))
      (content-type "application/json")
      (status (or status-code 200))))


;; Utilitários
(defn random-delay []
  (+ (* 60 1000) (rand-int (* 4 60 1000)))) ;; random delay between 1 and 5 minutes

(defn random-decision []
  (if (< (rand) 0.5) "approved" "rejected")) ;; 50% chance for either outcome

(defn random-interest-rate []
  (+ 10 (rand-int 10))) ;; random interest rate between 10% and 20%

  ;; Calcula valor de parcela com juros compostos aplicados
(defn calculate-installment-value [approved-value term-in-months interest-rate]
  (let [monthly-interest (/ interest-rate 100)
        total-amount (* approved-value (Math/pow (+ 1 monthly-interest) term-in-months))]
    (/ total-amount term-in-months)))

;; Calcula multa para pagamentos atrasados
(defn calculate-late-penalty [due-date paid-value]
  (let [days-late (max 0 (.between java.time.temporal.ChronoUnit/DAYS due-date (java.time.LocalDate/now)))
        penalty (* paid-value 0.02)
        daily-penalty (* paid-value 0.001 days-late)]
    (+ penalty daily-penalty)))


;; Loan Requests
(defn create-loan-request [loan-data]
  (sql/insert! datasource :loanRequests (assoc loan-data :status "pending")))

(defn get-loan-requests [customer-id]
  (sql/query datasource ["SELECT * FROM loanRequests WHERE customerid = ?::integer" customer-id]))

(defn get-loan-request [id]
  (sql/get-by-id datasource :loanRequests id))

(defn delete-loan-request [id]
  (let [request (get-loan-request id)]
    (if (and request (contains? #{"pending" "rejected"} (:loanrequests/status request)))
      (do
        (sql/delete! datasource :loanRequests {:id id})
        true)
      false)))


;; Loans
(defn create-loan [loan-data]
  (sql/insert! datasource :loans loan-data))

(defn get-loans [customer-id]
  (sql/query datasource ["SELECT * FROM loans WHERE customerid = ?::integer" customer-id]))

(defn get-loan [id]
  (sql/get-by-id datasource :loans id))


;; Loan Payments
(defn create-loan-payment [payment-data]
  (sql/insert! datasource :loanPayments payment-data))

(defn get-loan-payments [loan-id]
  (sql/query datasource ["SELECT * FROM loanPayments WHERE loanid = ?" loan-id]))


;; Cria as faturas para o empréstimo de acordo com a quantidade de parcelas
(defn create-payments [loan-id installment-value term-in-months]
  (doseq [month (range 1 (inc term-in-months))]
    (create-loan-payment {:loanid loan-id
                         :paidvalue installment-value 
                         :duedate (.plusMonths (java.time.LocalDate/now) month)
                         :status false})))

;; Função para agendar a criação de pagamentos recorrentes
(defn schedule-daily-payment-check []
  (go (loop []
        ;; verifica pagamentos vencidos e aplica penalidades
        (let [payments (sql/query datasource ["SELECT * FROM loanPayments WHERE status = FALSE AND duedate < CURRENT_DATE"])]
          (doseq [payment payments]
            (let [penalty (calculate-late-penalty (:loanpayments/duedate payment) (:loanpayments/paidvalue payment))
                  updated-payment {:loanpayments/latepenalty penalty}]
              (sql/update! datasource :loanPayments updated-payment {:id (:loanpayments/id payment)}))))
        (<! (timeout (* 24 60 60 1000))) ;; espera 24 horas antes da próxima verificação
        (recur))))

;; Cria o empréstimo e a primeira fatura após a aprovação
(defn approve-loan [loan-request]
  (let [interest-rate (random-interest-rate)
        installment-value (calculate-installment-value (:loanrequests/requestedvalue loan-request)
                                                        (:loanrequests/terminmonths loan-request)
                                                        interest-rate)
        approved-loan {:customerid (:loanrequests/customerid loan-request)
                  :approvedvalue (:loanrequests/requestedvalue loan-request) 
                  :requestid (:loanrequests/id loan-request)
                  :interestrate interest-rate
                  :installmentvalue installment-value}]
    (let [loan (create-loan approved-loan)
          loan-id (:loans/id loan)]
      (create-payments loan-id installment-value (:loanrequests/terminmonths loan-request)))))

;; Background task to process loan approval/denial
(defn process-loan-request [loan-request]
  (future
    (Thread/sleep (random-delay))
    (let [status (random-decision)]
      (let [update-data (if (= status "approved")
                         (assoc {:status status} :approvedAt (java.time.LocalDateTime/now))
                    {:status status})]
        (sql/update! datasource :loanRequests update-data {:id (:loanrequests/id loan-request)})
        (when (= status "approved")
          (approve-loan loan-request))))))


(defroutes app-routes
  (GET "/" []
    (json-response {:message "Welcome to the Oxebanking Loan API"}))


  ;; Loan Requests
  (POST "/loans/request" {body :body}
    (let [loan-data (json/parse-string (slurp body) true)
          result (create-loan-request loan-data)]
      (process-loan-request result)
      (json-response {:id (:loanrequests/id result)
                      :customerId (:loanrequests/customerid loan-data)
                      :requestedValue (:loanrequests/requestedvalue loan-data)
                      :termInMonths (:loanrequests/terminmonths loan-data)
                      :status (:loanrequests/status loan-data)
                      :approvedAt (:loanrequests/approvedat loan-data)
                      :createdAt (:loanrequests/createdat loan-data)
                      :updatedAt (:loanrequests/updatedat loan-data)})))

  (GET "/loans/request" {params :params}
    (let [customer-id (:customerId params)
          loan-requests (get-loan-requests customer-id)]
      (json-response (map #(hash-map :id (:loanrequests/id %)
                                     :customerId (:loanrequests/customerid %)
                                     :requestedValue (:loanrequests/requestedvalue %)
                                     :termInMonths (:loanrequests/terminmonths %)
                                     :status (:loanrequests/status %)
                                     :approvedAt (:loanrequests/approvedat %)
                                     :createdAt (:loanrequests/createdat %)
                                     :updatedAt (:loanrequests/updatedat %))
                                 loan-requests))))
      
  (GET "/loans/request/:id" [id]
    (let [request (get-loan-request (Integer/parseInt id))]
      (if request
        (json-response {:id (:loanrequests/id request)
                        :customerId (:loanrequests/customerid request)
                        :requestedValue (:loanrequests/requestedvalue request)
                        :termInMonths (:loanrequests/terminmonths request)
                        :status (:loanrequests/status request)
                        :approvedAt (:loanrequests/approvedat request)
                        :createdAt (:loanrequests/createdat request)
                        :updatedAt (:loanrequests/updatedat request)})
        (json-response {:error "Loan request not found"} 404))))

  (DELETE "/loans/request/:id" [id]
    (if (delete-loan-request (Integer/parseInt id))
      (json-response {:message "Loan request deleted"})
      (json-response {:error "Cannot delete loan request - not found or already approved"} 400)))


  ;; Loans
  (GET "/loans" {params :params}
    (let [customer-id (:customerId params)
          loans (get-loans customer-id)]
      (json-response (map #(hash-map :id (:loans/id %)
                                     :requestId (:loans/requestid %)
                                     :customerId (:loans/customerid %)
                                     :approvedValue (:loans/approvedvalue %)
                                     :interestRate (:loans/interestrate %)
                                     :installmentValue (:loans/installmentvalue %)
                                     :createdAt (:loans/createdat %)
                                     :updatedAt (:loans/updatedat %))
                                 loans))))

  (GET "/loans/:id" [id]
    (let [loan (get-loan (Integer/parseInt id))]
      (if loan
        (json-response {:id (:loans/id loan)
                        :requestId (:loans/requestid loan)
                        :customerId (:loans/customerid loan)
                        :approvedValue (:loans/approvedvalue loan)
                        :interestRate (:loans/interestrate loan)
                        :installmentValue (:loans/installmentvalue loan)
                        :createdAt (:loans/createdat loan)
                        :updatedAt (:loans/updatedat loan)})
        (json-response {:error "Loan not found"} 404))))


  ;; Loan Payments
  (POST "/loans/:id/payments" {body :body {id :id} :params}
    (let [payment-data (json/parse-string (slurp body) true)
          loan-id (Integer/parseInt id)
          loan-payment-id (:id payment-data)
          payment-date (java.time.LocalDate/now)
          updated-payment {:loanpayments/id loan-payment-id
                          :loanpayments/status true
                          :loanpayments/paymentdate payment-date}]
      (sql/update! datasource :loanPayments updated-payment {:id loan-payment-id})
      (let [loan-payment (sql/get-by-id datasource :loanPayments loan-payment-id)]
        (json-response {:id (:loanpayments/id loan-payment)
                        :loanId (:loanpayments/loanid loan-payment)
                        :paidValue (:loanpayments/paidvalue loan-payment)
                        :latePenalty (:loanpayments/latepenalty loan-payment)
                        :paymentDate (:loanpayments/paymentdate loan-payment)
                        :status (:loanpayments/status loan-payment)
                        :createdAt (:loanpayments/createdat loan-payment)
                        :updatedAt (:loanpayments/updatedat loan-payment)
                        :dueDate (:loanpayments/duedate loan-payment)}))))

  (GET "/loans/:id/payments" [id]
    (let [payments (get-loan-payments (Integer/parseInt id))]
      (json-response (map #(hash-map :id (:loanpayments/id %)
                                     :loanId (:loanpayments/loanid %)
                                     :paidValue (:loanpayments/paidvalue %)
                                     :latePenalty (:loanpayments/latepenalty %)
                                     :paymentDate (:loanpayments/paymentdate %)
                                     :status (:loanpayments/status %)
                                     :createdAt (:loanpayments/createdat %)
                                     :updatedAt (:loanpayments/updatedat %)
                                     :dueDate (:loanpayments/duedate %))
                                 payments)))))


(defn -main [& m]
  (schedule-daily-payment-check)
  (run-jetty (handler/site app-routes) {:port 3000 :join? false}))
