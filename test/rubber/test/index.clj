(ns rubber.test.index
  "Index testing manipulation"
  (:require
   [rubber.node :refer (stop connect)]
   [rubber.core :as r]
   [clojure.test :refer (deftest is use-fixtures)]))

(defn setup [f]
  (connect {:hosts ["http://localhost:9200"]})
  (f))

(use-fixtures :once setup)

(def types
  {:mappings {:person {:properties {:name {:type "text"}}}}})

(defn gen-uuid []
  (.replace (str (java.util.UUID/randomUUID)) "-" ""))

(deftest index-creation
  (let [uuid (gen-uuid)
        idx (keyword (str "people-" uuid))]
    (is (= 200 (r/create-index idx types)))
    (is (= {idx types} (r/mappings idx :person)))
    (is (some #{(name idx)} (map :index (r/list-indices))))
    (is (r/exists? idx))
    (is (= 200 (r/delete-index idx)))
    (is (not (some #{(name idx)} (map :index (r/list-indices)))))
    (is (nil? (r/mappings idx :person)))))

(deftest core-functions
  (let [uuid (gen-uuid)
        idx (keyword (str "people-" uuid))]
    (is (= 200 (r/create-index idx types)))
    (is (= {idx types} (r/mappings idx :person)))
    (let [id (r/create idx :person {:name "joe"})]
      (is (string? id))
      (is (= 200 (r/delete idx :person id))))
    (is (= 200 (r/delete-index idx)))))
