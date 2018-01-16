(ns clj-momo.lib.es.index-test
  (:require [clj-momo.test-helpers.core :as mth]
            [clojure.test :refer [deftest is join-fixtures testing use-fixtures]]
            [clj-momo.lib.es
             [conn :as es-conn]
             [index :as es-index]]
            [test-helpers.core :as th]))

(use-fixtures :once
  mth/fixture-schema-validation
  th/fixture-properties)

(deftest index-uri-test
  (testing "should generate a valid index URI"
    (is (= (es-index/index-uri "http://127.0.0.1" "test")
           "http://127.0.0.1/test"))))

(deftest template-uri-test
  (testing "should generate a valid template URI"
    (is (= (es-index/template-uri "http://127.0.0.1" "test")
           "http://127.0.0.1/_template/test"))))

(deftest ^:integration index-crud-ops
  (testing "with ES conn test setup"

    (let [conn (es-conn/connect
                (th/get-es-config))]

      (testing "all ES Index CRUD operations"
        (let [index-create-res
              (es-index/create! conn "test_index"
                                {:settings {:number_of_shards 1
                                            :number_of_replicas 1}})
              index-get-res (es-index/get conn "test_index")
              index-close-res (es-index/close! conn "test_index")
              index-open-res (es-index/open! conn "test_index")
              index-delete-res (es-index/delete! conn "test_index")]

          (es-index/delete! conn "test_index")

          (is (true? (boolean index-create-res)))
          (is (= {:test_index
                  {:aliases {},
                   :mappings {},
                   :settings
                   {:index
                    {:number_of_shards "1"
                     :number_of_replicas "1"
                     :provided_name "test_index"}}}}

                 (update-in index-get-res
                            [:test_index :settings :index]
                            dissoc
                            :creation_date
                            :uuid
                            :version)))
          (is (= {:acknowledged true} index-open-res))
          (is (= {:acknowledged true} index-close-res))
          (is (true? (boolean index-delete-res))))))))
