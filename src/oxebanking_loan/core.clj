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
              :host "db"
              :port 5432})

(def datasource (jdbc/get-datasource db-spec))

(defn json-response [data & [status-code]]
  (-> (response (json/generate-string data))
      (content-type "application/json")
      (status (or status-code 200))))


;; Utilitários
(defn random-delay []
  (+ 1000 (rand-int (* 10 60)))) ;; random delay between 1 second and 1 minute

(defn random-decision []
  (if (< (rand) 0.9) "approved" "rejected")) ;; 50% chance for either outcome

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

(defn get-loan-requests []
  (sql/query datasource ["SELECT * FROM loanRequests"]))

(defn get-loan-request [id]
  (sql/get-by-id datasource :loanRequests id))

(defn delete-loan-request [id]
  (sql/delete! datasource :loanRequests {:id id}))


;; Loans
(defn create-loan [loan-data]
  (sql/insert! datasource :loans loan-data))

(defn get-loans []
  (sql/query datasource ["SELECT * FROM loans"]))

(defn get-loan [id]
  (sql/get-by-id datasource :loans id))


;; Loan Payments
(defn create-loan-payment [payment-data]
  (sql/insert! datasource :loanPayments payment-data))

(defn get-loan-payments [loan-id]
  (sql/query datasource ["SELECT * FROM loanPayments WHERE loanId = ?" loan-id]))


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
      (println "Loan request processed:" (:loanrequests/id loan-request) "Status:" status)
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
      (println "🚀🚀 Received loan request, id:" (:loanrequests/id result))
      (process-loan-request result)
      (json-response {:result result :message "Pedido recebido e em processamento."})))

  (GET "/loans/request" []
    (let [requests (get-loan-requests)]
      (json-response {:requests requests})))

  (GET "/loans/request/:id" [id]
    (let [request (get-loan-request (Integer/parseInt id))]
      (if request
        (json-response request)
        (json-response {:error "Loan request not found"} 404))))

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
  (POST "/loans/:id/payments" {body :body {id :id} :params}
    (println "🚀🚀 Received payment, id:" id)
    (let [payment-data (json/parse-string (slurp body) true)
          loan-id (Integer/parseInt id)
          loan-payment-id (:id payment-data)
          payment-date (java.time.LocalDate/now)
          updated-payment {:loanpayments/id loan-payment-id
                          :loanpayments/status true
                          :loanpayments/paymentdate payment-date}]
      (sql/update! datasource :loanPayments updated-payment {:id loan-payment-id})
      (let [loan-payment (sql/get-by-id datasource :loanPayments loan-payment-id)]
        (json-response {:result loan-payment :message "Pagamento recebido"}))))


  (GET "/loans/:id/payments" [id]
    (let [payments (get-loan-payments (Integer/parseInt id))]
      (json-response {:payments payments})))


  (defn -main [& args]
    (schedule-daily-payment-check)
    (run-jetty app-routes {:port 3000 :join? false})))
