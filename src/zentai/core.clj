(ns zentai.core
  "Commonly used comfort functions"
  (:refer-clojure :exclude (get))
  (:require
   [clojure.string :refer (split)]
   [taoensso.timbre :refer (refer-timbre)]
   [qbits.spandex :as s]
   [clj-time.core :as t]
   [clj-time.format :as f]
   [zentai.node :refer (connection)])
  (:import
   [org.elasticsearch.client ResponseException]
   java.io.StringWriter
   java.io.PrintWriter))

(refer-timbre)

(defn error-m [e]
  (let [sw (StringWriter.) p (PrintWriter. sw)]
    (.printStackTrace e p)
    (error (.getMessage e) (.toString sw))))

(defn- ok [resp]
  (#{200 201} (:status resp)))

(defn- illegal [e]
  (instance? java.lang.IllegalStateException e))

(defn- reactor-stopped
  "Used when the connection is reset"
  [e]
  (let [c "Request cannot be executed; I/O reactor status: STOPPED"]
    (and (illegal e) (= (-> e Throwable->map :cause) c))))

(defn pretty-error
  "A pretty print error log"
  [m]
  (let [st (java.io.StringWriter.)]
    (binding [*out* st]
      (clojure.pprint/pprint m))
    (error (.toString st))))

(defn- handle-ex [e]
  (when-not (reactor-stopped e)
    (error-m e)
    (when (= (class e) ResponseException)
      (pretty-error (s/response-ex->response e)))
    (when-let [data (ex-data e)]
      (pretty-error data))
    (throw e)))

(defn- exists-call
  [target]
  (try
    (ok (s/request (connection) {:url target :method :head}))
    (catch Exception e
      (when-not (= 404 (:status (ex-data e)))
        (handle-ex e)))))

(defn exists?
  "Check if index exists or instance with id existing within an index"
  ([index]
   (exists-call [index]))
  ([index t id]
   (exists-call [index t id])))

(defn- delete-call
  [target]
  (try
    (ok (s/request (connection) {:url target :method :delete}))
    (catch Exception e
      (handle-ex e))))

(defn delete
  "Delete all under index or a single id"
  ([index t]
   (delete-call [index t]))
  ([index t id]
   (delete-call [index t id])))

(defn delete-all
  [index]
  (try
    (ok (s/request (connection) {:url [index :_delete_by_query] :method :post :body {:query {:match_all {}}}}))
    (catch Exception e
      (handle-ex e))))

(defn put-call
  [target m]
  (try
    (ok (s/request (connection) {:url target :method :put :body m}))
    (catch Exception e
      (handle-ex e))))

(defn put [index t id m]
  (put-call [index t id] m))

(defn get-call [target]
  (s/request (connection) {:url target :method :get}))

(defn get [index t id]
  (try
    (get-in (get-call [index t id]) [:body :_source])
    (catch Exception e
      (when-not (= 404 (:status (ex-data e)))
        (handle-ex e)))))

(defn bulk-get
  [index t ids]
  {:pre [(not (empty? ids))]}
  (try
    (let [{:keys [body] :as resp} (s/request (connection) {:url [index t :_mget] :method :get :body {:ids ids}})]
      (when (ok resp)
        (into {} (map (juxt :_id :_source) (filter :found (body :docs))))))
    (catch Exception e
      (when-not (= 404 (:status (ex-data e)))
        (handle-ex e)))))

(defn refresh-index
  "Refresh the index in order to get the lastest operations available for search"
  [index]
  (try
    (let [resp (s/request (connection) {:url [index :_refresh] :method :post})]
      (when-not (ok resp)
        (throw (ex-info "failed to refresh" {:resp resp :index index}))))
    (catch Exception e
      (handle-ex e))))

(defn create
  "Persist instance m of and return generated id"
  [index t m]
  (try
    (let [{:keys [status body] :as resp} (s/request (connection) {:url [index t] :method :post :body m})]
      (when-not (ok resp)
        (throw (ex-info "failed to create" {:resp resp :m m :index index})))
      (body :_id))
    (catch Exception e
      (handle-ex e))))

(def ^:const default-settings {:settings {:number_of_shards 1}})

(defn create-index
  "Create an index with provided mappings"
  [index {:keys [mappings] :as spec}]
  {:pre [mappings]}
  (ok (s/request (connection) {:url [index] :method :put
                               :body (merge default-settings spec)})))

(defn delete-index
  "Delete an index"
  [idx]
  (delete-call [idx]))

(defn list-indices []
  (let [ks [:health :status :index :uuid :pri :rep :docs.count :docs.deleted :store.size :pri.store.size]]
    (map #(zipmap ks (filter (comp not empty?) (split % #"\s")))
         (split (:body (s/request (connection) {:url [:_cat :indices] :method :get})) #"\n"))))

(defn clear
  "Clear index type"
  [index t]
  (when (exists? index)
    (info "Clearing index" index)
    (delete index t)))

(defn all
  "An all query using match all on provided index this should use scrolling for 10K systems"
  [index]
  (let [query {:size 10000 :query {:match_all {}}}
        {:keys [body]} (s/request (connection) {:url [index :_search] :method :get :body query})]
    (mapv (juxt :_id :_source) (get-in body [:hits :hits]))))

(defn search
  "An Elasticsearch search query"
  [index input]
  (try
    (let [m {:url [index :_search] :method :get :body input}
          {:keys [body] :as resp} (s/request (connection) m)]
      (when (ok resp)
        (mapv (juxt :_id :_source) (get-in body [:hits :hits]))))
    (catch Exception e
      (when-not (= 404 (:status (ex-data e)))
        (handle-ex e)))))

(defn delete-by
  "Delete by query like {:match {:type \"nmap scan\"}}"
  [index t query]
  (try
    (s/request (connection) {:url [index t :_delete_by_query] :method :post :body {:query query}})
    (catch Exception e
      (handle-ex e))))

(def conn-prefix (atom :default))

(defn prefix-switch
  "Change es prefix"
  [k]
  (reset! conn-prefix k))

(defn mappings
  "get index mappings"
  [idx t]
  (try
    (:body (s/request (connection) {:url [idx :_mappings t] :method :get}))
    (catch Exception e
      (when-not (= 404 (:status (ex-data e)))
        (handle-ex e)))))
