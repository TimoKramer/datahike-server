(ns datahike-server.integration-test-json
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clj-http.client :as client]
            [datahike-server.core :refer [start-all stop-all]]))

(defn parse-body [{:keys [body]}]
  (if-not (empty? body)
    (edn/read-string body)
    ""))

(defn api-request
  ([method url]
   (api-request method url nil nil))
  ([method url data]
   (api-request method url data nil))
  ([method url data opts]
   (-> (client/request (merge {:url (str "http://localhost:3333" url)
                               :method method
                               :content-type "application/json"
                               :accept "application/json"}
                              (when (or (= method :post) data)
                                {:body (str data)})
                              opts))
       parse-body)))

(defn setup-db [f]
  (start-all)
  (f)
  (stop-all))

(use-fixtures :once setup-db)

(deftest swagger-test
  (testing "Swagger Json"
    (is (= {:title "Datahike API"
            :description "Transaction and search functions"}
           (:info (api-request :get
                               "/swagger.json"
                               nil
                               {:headers {:authorization "token neverusethisaspassword"}}))))))

(deftest transact-test
  (testing "Transact some data"
    (is (= "foo"
           (api-request :post "/transact"
                        {:tx-data [{:name "Alice", :age 20}
                                   {:name "Bob", :age 30}
                                   {:name "Charlie", :age 40}
                                   {:age 15}]
                         :tx-meta [{}]})))))

(deftest q-test
  (testing "Executes a datalog query"
    (is (= "Alice"
           (second (first (api-request :post "/q"
                                       {:query '[:find ?e ?n :in $ ?n :where [?e :name ?n]]
                                        :args ["Alice"]}
                                       {:headers {:authorization "token neverusethisaspassword"
                                                  :db-name "sessions"}})))))))

