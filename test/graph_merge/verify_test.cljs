(ns graph-merge.verify-test
  (:require [cljs.test :refer [deftest is testing]]
            [graph-merge.verify :as verify]
            [logseq.common.uuid :as common-uuid]))

(def page-uuid #uuid "a0000000-0000-4000-8000-000000000001")

(deftest expected-block-counts-per-page
  (let [export {:pages-and-blocks
                [{:page {:build/journal 20260910} :blocks [{:block/title "a"} {:block/title "b"}]}
                 {:page {:block/title "P" :block/uuid page-uuid}
                  :blocks [{:block/title "parent" :build/children [{:block/title "child"}]}]}
                 {:page {:block/title "Empty" :block/uuid #uuid "a0000000-0000-4000-8000-000000000002"} :blocks []}]}]
    (is (= {(common-uuid/gen-uuid :journal-page-uuid 20260910) 2
            page-uuid 2
            #uuid "a0000000-0000-4000-8000-000000000002" 0}
           (verify/expected-block-counts export)))))

(deftest shortfalls-are-missing-pages-or-missing-blocks
  (testing "extra blocks are fine (a fresh graph can seed built-in pages); fewer or missing are not"
    (is (= [{:uuid page-uuid :expected 3 :actual 2}
            {:uuid #uuid "a0000000-0000-4000-8000-000000000002" :expected 1 :actual 0}]
           (verify/shortfalls {page-uuid 3
                               #uuid "a0000000-0000-4000-8000-000000000002" 1
                               #uuid "a0000000-0000-4000-8000-000000000003" 1}
                              {page-uuid 2
                               #uuid "a0000000-0000-4000-8000-000000000003" 4})))))

(deftest asset-problems-are-found-before-writing
  (let [files [{:graph "A" :uuid page-uuid :type "png" :checksum "good"}
               {:graph "A" :uuid #uuid "a0000000-0000-4000-8000-000000000002" :type "png" :checksum "expected"}
               {:graph "B" :uuid #uuid "a0000000-0000-4000-8000-000000000003" :type "pdf" :checksum "x"}]
        checksum-of {page-uuid "good" #uuid "a0000000-0000-4000-8000-000000000002" "changed"}]
    (is (= [{:graph "A" :uuid #uuid "a0000000-0000-4000-8000-000000000002" :problem :checksum-mismatch}
            {:graph "B" :uuid #uuid "a0000000-0000-4000-8000-000000000003" :problem :missing-file}]
           (verify/asset-problems files (comp checksum-of :uuid))))))
