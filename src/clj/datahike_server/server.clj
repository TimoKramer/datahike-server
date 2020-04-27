(ns datahike-server.server
  (:require [reitit.ring :as ring]
            [reitit.coercion.spec]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.ring.coercion :as coercion]
            [reitit.dev.pretty :as pretty]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.multipart :as multipart]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.cors :refer [wrap-cors]]
            [muuntaja.core :as m]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [datahike-server.handlers :as h]
            [datahike-server.config :refer [config]]
            [datahike-server.database :refer [database]]
            [mount.core :refer [defstate]]
            [ring.adapter.jetty :refer [run-jetty]])
  (:import (java.util UUID)))

(s/def ::entity any?)
(s/def ::tx-data (s/coll-of ::entity))
(s/def ::tx-meta (s/coll-of ::entity))
(s/def ::transactions (s/keys :req-un [::tx-data] :opt-un [::tx-meta]))

(s/def ::query (s/coll-of any?))
(s/def ::args (s/coll-of any?))
(s/def ::limit number?)
(s/def ::offset number?)
(s/def ::query-request (s/keys :req-un [::query]
                               :opt-un [::args ::limit ::offset]))

(s/def ::selector (s/coll-of any?))
(s/def ::eid any?)
(s/def ::pull-request (s/keys :req-un [::selector ::eid]))

(s/def ::eids (s/coll-of ::eid))
(s/def ::pull-many-request (s/keys :req-un [::selector ::eids]))

(s/def ::index #{:eavt :aevt :avet})
(s/def ::components (s/coll-of any?))
(s/def ::datoms-request (s/keys :req-un [::index] :opt-un [::components]))

(s/def ::entity-request (s/keys :req-un [::eid]))

(s/def ::backend #{:mem :file :pg :level})
(s/def ::username string?)
(s/def ::password string?)
(s/def ::path string?)
(s/def ::host string?)
(s/def ::port number?)
(s/def ::store (s/keys :req-un [::backend ::path]
                       :opt-un [::username ::password ::host ::port]))
(s/def ::schema-on-read boolean?)
(s/def ::temporal-index boolean?)
(s/def ::new-database (s/keys :req-un [::store]
                              :opt-un [::schema-on-read ::temporal-index]))

(s/def ::db-id string?)

(def routes
  [["/swagger.json"
    {:get {:no-doc  true
           :swagger {:info {:title       "Datahike API"
                            :description "Transaction and search functions"}}
           :handler (swagger/create-swagger-handler)}}]

   ["/databases"
    {:swagger {:tags ["database"]}
     :get {:summary "List available databases."
           :handler h/list-databases}

     :post {:summary "Creates a new database."
            :parameters {:body ::new-database}
            :handler h/create-database}}]

   ["/echo"
    {:get {:handler (fn [request]
                      (clojure.pprint/pprint request)
                      {:status 200})}}]

   ["/databases/:id"
    {:delete {:summary "Deletes database."
              :parameters {:params ::db-id}
              :handler h/delete-database}}]

   ["/databases/:id/connections"
    {:post {:summary "Connect to database."
            :parameters {:params ::db-id}
            :handler h/connect}}]

   ["/transact"
    {:swagger {:tags ["transact"]}
     :post {:summary "Applies transaction to the underlying database value."
            :parameters {:body ::transactions}
            :handler h/transact}}]

   ["/q"
    {:swagger {:tags ["search"]}
     :post {:summary "Executes a datalog query."
            :parameters {:body ::query-request}
            :handler h/q}}]

   ["/pull"
    {:swagger {:tags ["search"]}
     :post {:summary "Fetches data from database using recursive declarative description."
            :parameters {:body ::pull-request}
            :handler h/pull}}]

   ["/pull-many"
    {:swagger {:tags ["search"]}
     :post {:summary "Same as [[pull]], but accepts sequence of ids and returns sequence of maps."
            :parameters {:body ::pull-many-request}
            :handler h/pull-many}}]

   ["/datoms"
    {:swagger {:tags ["search"]}
     :post {:summary "Index lookup. Returns a sequence of datoms (lazy iterator over actual DB index) which components (e, a, v) match passed arguments."
            :parameters {:body ::datoms-request}
            :handler h/datoms}}]

   ["/seek datoms"
    {:swagger {:tags ["search"]}
     :post {:summary "Similar to [[datoms]], but will return datoms starting from specified components and including rest of the database until the end of the index."
            :parameters {:body ::datoms-request}
            :handler h/seek-datoms}}]

   ["/tempid"
    {:swagger {:tags ["utils"]}
     :get {:summary "Allocates and returns an unique temporary id."
           :handler h/tempid}}]

   ["/entity"
    {:swagger {:tags ["search"]}
     :post {:summary "Retrieves an entity by its id from database."
            :parameters {:body ::entity-request}
            :handler h/entity}}]

   ["/schema"
    {:swagger {:tags ["utils"]}
     :get {:summary "Fetches current schema"
           :handler h/schema}}]


   ])

(defn wrap-db-connection [handler]
  (fn [request]
    (if-let [db-id (get-in request [:headers "Database-ID"])]
      (if-let [conn (get-in @database [:connections (java.util.UUID/fromString db-id)])]
        (handler (assoc request :conn conn))
        (handler request))
      (handler request))))

(def route-opts
  {;;:reitit.middleware/transform dev/print-request-diffs ;; pretty diffs
   ;; :validate spec/validate ;; enable spec validation for route data
   ;;:reitit.spec/wrap spell/closed ;; strict top-level validation
   :exception pretty/exception
   :data      {:coercion   reitit.coercion.spec/coercion
               :muuntaja   m/instance
               :middleware [swagger/swagger-feature
                            parameters/parameters-middleware
                            muuntaja/format-negotiate-middleware
                            muuntaja/format-response-middleware
                            exception/exception-middleware
                            muuntaja/format-request-middleware
                            coercion/coerce-response-middleware
                            coercion/coerce-request-middleware
                            multipart/multipart-middleware
                            ]}})

(def app
  (-> (ring/ring-handler
       (ring/router routes route-opts)
       (ring/routes
        (swagger-ui/create-swagger-ui-handler
         {:path   "/"
          :config {:validatorUrl     nil
                   :operationsSorter "alpha"}})
        (ring/create-default-handler)))
      (wrap-cors :access-control-allow-origin [#"http://localhost" #"http://localhost:8080"]
                 :access-control-allow-methods [:get :put :post :delete])))

(defn start-server []
  (run-jetty app (:server config)))

(defstate server
  :start (start-server)
  :stop (.stop server))

