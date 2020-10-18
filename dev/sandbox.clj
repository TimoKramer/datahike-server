(ns sandbox
  (:require [datahike-server.database :refer [conns]]
            [clojure.edn :as edn]
            [clj-http.client :as client]
            [datahike-server.core :refer [start-all stop-all]]
            [muuntaja.core :as m]))

(comment

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
                                 :content-type "application/edn"
                                 :accept "application/edn"}
                                (when (or (= method :post) data)
                                  {:body (str data)})
                                opts))
         parse-body)))

  (def conn (get conns "onedb"))

  (do
    (stop-all)
    (start-all))

  (api-request :post "/q"
               {:query '[:find ?e ?n :in $ ?n :where [?e :name ?n]]}
               {:headers {:db-name "onedb"}})

  (api-request :post "/transact"
               {:tx-data [{:name "Alice", :age 20}
                          {:name "Bob", :age 30}
                          {:name "Charlie", :age 40}
                          {:age 15}]
                :tx-meta [{}]}
               {:headers {:db-name "onedb"}})

  ;;;;;;;;;;;;;;;
  ;; Testing JSON Support
  ;;;;;;;;;;;;;;;
  (require '[jsonista.core :as j])
  (def query (-> {:query '{:find ?name :where [_ :name ?name]}}
                (j/write-value-as-bytes j/default-object-mapper)
                (j/read-value j/keyword-keys-object-mapper)))
  query
  (clojure.pprint/pprint m/default-options)
  (def new-muun
    (m/create))
  (def conn (get conns "onedb"))
  (d/transact conn {:tx-data [{:name "Alice", :age 20} {:name "Bob", :age 30} {:name "Charlie", :age 40} {:age 15}]
                    :tx-meta [{}]})
  (d/q '[:find ?e :where [_ :name ?e]]
       @conn)
  (q {:parameters {:body query}
      :conn conn
      :db nil}))
