(ns clj-momo.lib.es.document-test
  (:require [clj-momo.lib.es
             [conn :as es-conn]
             [document :as es-doc]
             [index :as es-index]
             [query :as query]]
            [clj-momo.test-helpers.core :as mth]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [test-helpers.core :as th]))

(use-fixtures :once
  mth/fixture-schema-validation
  th/fixture-properties)

(deftest search-uri-test
  (testing "should generate a valid _search uri"
    (is (= "http://localhost:9200/ctia_tool/tool/_search"
           (es-doc/search-uri "http://localhost:9200"
                              "ctia_tool"
                              "tool")))
    (is (= "http://localhost:9200/ctia_tool/_search"
           (es-doc/search-uri "http://localhost:9200"
                              "ctia_tool"
                              nil)))
    (is (= "http://localhost:9200/_search"
           (es-doc/search-uri "http://localhost:9200"
                              nil
                              nil)))))

(deftest delete-by-query-uri-test
  (testing "should generate a valid delete_by_query uri"
    (is (= "http://localhost:9200/ctim/_delete_by_query"
           (es-doc/delete-by-query-uri "http://localhost:9200"
                                       ["ctim"]
                                       nil)))
    (is (= "http://localhost:9200/ctim/malware/_delete_by_query"
           (es-doc/delete-by-query-uri "http://localhost:9200"
                                       ["ctim"]
                                       "malware")))
    (is (= "http://localhost:9200/ctim%2Cctia/malware/_delete_by_query"
           (es-doc/delete-by-query-uri "http://localhost:9200"
                                       ["ctim", "ctia"]
                                       "malware")))))

(deftest index-doc-uri-test
  (testing "should generate a valid doc URI"
    (is (= "http://127.0.0.1/test_index/test_mapping/test"
           (es-doc/index-doc-uri "http://127.0.0.1"
                                  "test_index"
                                  "test_mapping"
                                  "test")))
    (is (= "http://127.0.0.1/test_index/test_mapping/test%2Ffoo%2Fbar"
           (es-doc/index-doc-uri "http://127.0.0.1"
                                  "test_index"
                                  "test_mapping"
                                  "test/foo/bar")))))

(deftest update-doc-uri-test
  (is (= (es-doc/update-doc-uri "http://127.0.0.1"
                                "test_index"
                                "test_mapping"
                                "test"
                                42)
         "http://127.0.0.1/test_index/test_mapping/test/_update?retry_on_conflict=42"))  )


(deftest params->pagination-test
  (is (= {:size 100
          :sort {"field1" {:order :asc}}}
         (es-doc/params->pagination {:sort_by :field1})))

  (is (= {:size 100
          :sort {"field1" {:order :desc}}}
         (es-doc/params->pagination {:sort_by "field1:desc"})
         (es-doc/params->pagination {:sort_by "field1:desc"
                                     :sort_order :asc})))

  (is (= {:size 100
          :sort {"field1" {:order :desc}
                 "field2" {:order :asc}
                 "field3" {:order :desc}}}
         (es-doc/params->pagination {:sort_by "field1:desc,field2:asc,field3:desc"})
         (es-doc/params->pagination {:sort_by "field1:desc,field2:asc,field3:desc"
                                     :sort_order :asc})))

  (is (= {:size 100
          :from 1000
          :sort {"field1" {:order :asc}}}
         (es-doc/params->pagination {:sort_by :field1
                                     :offset 1000})))

  (is (= {:size 10000
          :from 1000
          :sort {"field1" {:order :asc}}}
         (es-doc/params->pagination {:sort_by :field1
                                     :offset 1000
                                     :limit 10000})))

  (is (= {:size 10000
          :from 0
          :search_after ["value1"]
          :sort {"field1" {:order :asc}}}
         (es-doc/params->pagination {:sort_by :field1
                                     :offset 1000
                                     :limit 10000
                                     :search_after ["value1"]})
         (es-doc/params->pagination {:sort_by :field1
                                     :limit 10000
                                     :search_after ["value1"]}))))

(deftest generate-es-params-test
  (is (= {:size 10 :from 20}
         (es-doc/generate-es-params nil nil {:limit 10 :offset 20}))
      "generate-es-params should properly format pagination parameters")
  (is (= {:size 100}
         (es-doc/generate-es-params nil nil {}))
      "generate-es-params should apply default query values")
  (is (= {:query {:match_all {}}
          :size 10}
         (es-doc/generate-es-params {:match_all {}} nil {:limit 10}))
      "generate-es-params should set :query with query passed as parameter")
  (let [aggs {:docs_by_week
              {:date_histogram
               {:field "timestamp"
                :interval "week"}}}]
    (is (= {:aggs aggs :size 0}
           (es-doc/generate-es-params nil aggs {:limit 0}))
        "generate-es-params should set :aggs with aggs passed as parameter")))

(deftest ^:integration document-crud-ops
  (testing "with ES conn test setup"
    (let [conn (es-conn/connect
                (th/get-es-config))]

      (es-index/delete! conn "test_index")

      (testing "all ES Document CRUD operations"
        (let [sample-doc {:id "test_doc"
                          :foo "bar is a lie"
                          :test_value 42}
              sample-docs
              (repeatedly 10 #(hash-map :id (.toString (java.util.UUID/randomUUID))
                                        :_index "test_index"
                                        :_type "test_mapping"
                                        :bar "foo"))
              get-sample-doc #(es-doc/get-doc conn
                                              "test_index"
                                              "test_mapping"
                                              (:id sample-doc)
                                              {})]
          (testing "create-doc and get-doc"
            (is (nil? (get-sample-doc)))
            (is (= sample-doc
                   (es-doc/create-doc conn
                                      "test_index"
                                      "test_mapping"
                                      sample-doc
                                      "true")))
            (is (= sample-doc (get-sample-doc)))
            (testing "existing doc"
              (is (thrown? clojure.lang.ExceptionInfo
                           (es-doc/create-doc conn
                                              "test_index"
                                              "test_mapping"
                                              sample-doc
                                              "true"))))
            (testing "with field selection"
              (is (= {:foo "bar is a lie"}
                     (es-doc/get-doc conn
                                     "test_index"
                                     "test_mapping"
                                     (:id sample-doc)
                                     {:_source ["foo"]})))))
          (testing "update-doc"
            (let [update1 {:test_value 44}
                  updated-doc1 (into sample-doc update1)
                  update2 {:test_value 55}
                  updated-doc2 (into sample-doc update2)]
              (is (= updated-doc1
                     (es-doc/update-doc conn
                                        "test_index"
                                        "test_mapping"
                                        (:id sample-doc)
                                        update1
                                        "true")))
              (is (= updated-doc1 (get-sample-doc)))
              (testing "with params"
                (is (= updated-doc2
                       (es-doc/update-doc conn
                                          "test_index"
                                          "test_mapping"
                                          (:id sample-doc)
                                          update2
                                          "true"
                                          {:retry-on-conflict 10})))
                (is (= updated-doc2 (get-sample-doc))))))
          (testing "index-doc"
            (testing "updating a field"
              (let [indexed-doc (assoc sample-doc :test_value 66)]
                (is (= indexed-doc
                       (es-doc/index-doc conn
                                         "test_index"
                                         "test_mapping"
                                         indexed-doc
                                         "true")))
                (is (= indexed-doc (get-sample-doc)))))
            (testing "removing a field"
              (let [indexed-doc (dissoc sample-doc :test_value)]
                (is (= indexed-doc
                       (es-doc/index-doc conn
                                         "test_index"
                                         "test_mapping"
                                         indexed-doc
                                         "true")))
                (is (= indexed-doc (get-sample-doc)))
                ;; restore with the initial values
                (es-doc/index-doc conn
                                  "test_index"
                                  "test_mapping"
                                  sample-doc
                                  "true"))))
          (testing "bulk-create-doc"
            (is (= sample-docs
                   (es-doc/bulk-create-doc conn
                                           sample-docs
                                           "true")))
            (testing "with partioning"
              (let [sample-docs-2 (map #(assoc % :test_value 43) sample-docs)]
                (is (= sample-docs-2
                       (es-doc/bulk-create-doc conn
                                               sample-docs-2
                                               "true"
                                               0)))

                (is (= 10
                       (get-in (es-doc/search-docs conn
                                                   "test_index"
                                                   "test_mapping"
                                                   {:query_string {:query "*"}}
                                                   {:test_value 43}
                                                   {:sort_by "test_value"
                                                    :sort_order :desc})
                               [:paging :total-hits]))))))
          (is (= {:data [sample-doc]
                  :paging {:total-hits 1
                           :sort [42]}}
                 (es-doc/search-docs conn
                                     "test_index"
                                     "test_mapping"
                                     {:query_string {:query "bar"}}
                                     {:test_value 42}
                                     {:sort_by "test_value"
                                      :sort_order :desc})
                 (es-doc/search-docs conn
                                     "test_index"
                                     nil
                                     {:query_string {:query "bar"}}
                                     {:test_value 42}
                                     {:sort_by "test_value"
                                      :sort_order :desc})))

          (is (true?
               (es-doc/delete-doc conn
                                  "test_index"
                                  "test_mapping"
                                  (:id sample-doc)
                                  "true")))))

      (es-index/delete! conn "test_index"))))

(deftest partition-json-ops-test
  (is (= [["ops1"] ["ops2"] ["ops3--"]]
         (es-doc/partition-json-ops
          ["ops1" "ops2" "ops3--"]
          1))
      "All elements are in a group if the max size is exceeded")
  (is (= [["ops1" "ops2"] ["ops3--"]]
         (es-doc/partition-json-ops
          ["ops1" "ops2" "ops3--"]
          8))
      "The max size is used to partition ops")
  (is (= [["ops1" "ops2" "ops3--"]]
         (es-doc/partition-json-ops
          ["ops1" "ops2" "ops3--"]
          1000))
      "All ops are in the same group"))

(deftest ^:integration search_after-consistency-test
  (let [docs
        (let [make-id #(.toString (java.util.UUID/randomUUID))]
          (map
           #(hash-map :id (make-id)
                      :foo %
                      :test "ok")
           (range 1000)))
        conn (es-conn/connect
              (th/get-es-config))
        search-query #(get-in (es-doc/search-docs conn
                                                  "test_index"
                                                  "test_mapping"
                                                  nil
                                                  {}
                                                  {:limit 100})
                              [:paging :sort])]
    (es-index/delete! conn "test_index")
    (es-index/create! conn "test_index" {})
    (doseq [doc docs]
      (es-doc/create-doc conn
                         "test_index"
                         "test_mapping"
                         doc
                         "true"))
    (is (apply = (repeatedly 30 search-query)))
    (es-index/delete! conn "test_index")))

(deftest ^:integration count-test
  (let [sample-docs (mapv #(assoc {:_index "test_index"
                                   :_type "test_mapping"
                                   :foo :bar}
                                  :_id %)
                          (range 10))
        conn (es-conn/connect (th/get-es-config))]
    (es-index/delete! conn "test_index")
    (es-index/create! conn "test_index" {})
    (es-doc/bulk-create-doc conn sample-docs "true")
    (is (= 10
           (es-doc/count-docs conn "test_index" "test_mapping")
           (es-doc/count-docs conn "test_index" nil)
           (es-doc/count-docs conn "test_index" "test_mapping" {:term {:foo :bar}})
           (es-doc/count-docs conn "test_index" "test_mapping" {:match_all {}})))
    (is (= 3 (es-doc/count-docs conn "test_index" "test_mapping" {:ids {:values (range 3)}})))
    (es-index/delete! conn "test_index")))

(defn is-full-hits?
  [{:keys [_source _index _id]}]
  (boolean (and _source _index _id)))

(deftest ^:integration query-test
  (let [sample-docs (mapv #(assoc {:_index "test_index"
                                   :_type "test_mapping"
                                   :foo :bar
                                   :price %}
                                  :_id (str %))
                          (range 10))
        conn (es-conn/connect (th/get-es-config))
        sample-3-docs (->> (shuffle sample-docs)
                           (take 3))
        sample-3-ids (map :_id sample-3-docs)
        _ (es-index/delete! conn "test_index")
        _ (es-index/create! conn "test_index" {})
        _ (es-doc/bulk-create-doc conn sample-docs "true")
        ids-query-result-1 (es-doc/query conn
                                         "test_index"
                                         "test_mapping"
                                         (query/ids sample-3-ids)
                                         {})
        ids-query-result-2 (es-doc/query conn
                                         "test_index"
                                         "test_mapping"
                                         (query/ids sample-3-ids)
                                         {:full-hits? true})
        search_after-result (es-doc/query conn
                                          "test_index"
                                          "test_mapping"
                                          {:match_all {}}
                                          {:limit 2
                                           :sort ["price"]
                                           :search_after [5]})
        avg-aggs {:avg_price {:avg {:field :price}}}
        {data-aggs-1 :data
         aggs-1 :aggs
         paging-aggs-1 :paging} (es-doc/query conn
                                              "test_index"
                                              "test_mapping"
                                              {:match_all {}}
                                              avg-aggs
                                              {:limit 5})

        stats-aggs {:price_stats {:stats {:field :price}}}
        {data-aggs-2 :data
         aggs-2 :aggs} (es-doc/query conn
                                     "test_index"
                                     "test_mapping"
                                     {:match_all {}}
                                     stats-aggs
                                     {:limit 0})
        stats-aggs {:price_stats {:stats {:field :price}}}
        {data-aggs-3 :data
         aggs-3 :aggs} (es-doc/query conn
                                     "test_index"
                                     "test_mapping"
                                     (query/ids (map :_id (take 3 sample-docs)))
                                     stats-aggs
                                     {:limit 10})]

    (is (= (repeat 3 {:foo "bar"})
           (->> ids-query-result-1
                :data
                (map #(select-keys % [:foo]))))
        "querying with ids query without full-hits? param should return only source of selected docs in :data")
    (testing "when full-hits is set as true, each element of :data field should contains :_id :_source and :_index fields"
      (is (= (set sample-3-ids)
             (->> (:data ids-query-result-2)
                  (map :_id)
                  set)))
      (is (= (repeat 3 "bar")
             (->> (:data ids-query-result-2)
                  (map #(-> % :_source :foo)))))
      (is (= (repeat 3 "test_index")
             (->> (:data ids-query-result-2)
                  (map :_index)))))
    (is (not-any? is-full-hits? ids-query-result-2),
        "by default, full-hits? is set to true")

    (testing "sort and search_after params should be properly applied"
      (is (= '(6 7)
             (map :price (:data search_after-result))))
      (is (= [7]
             (-> search_after-result :paging :sort)
             (-> search_after-result :paging :next :search_after))))

    (testing "aggs parameter should be used to perform aggregations, while applying query and paging"
      (is (= 5 (count data-aggs-1)))
      (is (= 4.5 (-> aggs-1 :avg_price :value)))
      (is (= {:total-hits 10
              :next {:limit 5 :offset 5}}
             paging-aggs-1))
      (is (= 0 (count data-aggs-2)))
      (is (= {:count 10
              :min 0.0
              :max 9.0
              :avg 4.5
              :sum 45.0}
             (:price_stats aggs-2)))
      (is (= 3 (count data-aggs-3)))
      (is (= {:count 3
              :min 0.0
              :max 2.0
              :avg 1.0
              :sum 3.0}
             (:price_stats aggs-3))))
    ;; clean
    (es-index/delete! conn "test_index")))

(deftest ^:integration delete-by-query-test
  (let [sample-docs-1 (mapv #(assoc {:_index "test_index-1"
                                     :_type "test_mapping"
                                     :foo (if (< % 5)
                                            :bar1
                                            :bar2)}
                                    :_id %)
                            (range 10))
        sample-docs-2 (mapv #(assoc {:_index "test_index-2"
                                     :_type "test_mapping"
                                     :foo (if (< % 5)
                                            :bar1
                                            :bar2)}
                                    :_id %)
                            (range 10))
        conn (es-conn/connect (th/get-es-config))
        q-term (query/term :foo :bar2)
        q-ids-1 (query/ids ["0" "1" "2"])
        q-ids-2 (query/ids ["3" "4"])]
    (es-index/delete! conn "test_index")
    (es-index/create! conn "test_index" {})
    (es-doc/bulk-create-doc conn sample-docs-1 "true")
    (es-doc/bulk-create-doc conn sample-docs-2 "true")
    (is (= 5
           (:deleted (es-doc/delete-by-query conn
                                             ["test_index-1"]
                                             "test_mapping"
                                             q-term
                                             true
                                             "true")))
        "delete-by-query should delete all documents that match a query for given index and mapping")
    (is (= 5
           (:deleted (es-doc/delete-by-query conn
                                             ["test_index-2"]
                                             nil
                                             q-term
                                             true
                                             "true")))
        "delete-by-query should delete all documents that match a query for given index without specifying mapping")
    (is (= 6
           (:deleted (es-doc/delete-by-query conn
                                             ["test_index-1", "test_index-2"]
                                             "test_mapping"
                                             q-ids-1
                                             true
                                             "true")))
        "delete-by-query should properly apply deletion on all given indices")
    (is (seq (:task (es-doc/delete-by-query conn
                                            ["test_index-1", "test_index-2"]
                                            "test_mapping"
                                            q-ids-2
                                            false
                                            "true")))
        "delete-by-query with wait-for-completion? set to false should directly return an answer before deletion with a task id")
    (es-index/delete! conn "test_index")))
