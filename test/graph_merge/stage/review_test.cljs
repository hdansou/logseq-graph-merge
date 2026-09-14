(ns graph-merge.stage.review-test
  (:require [cljs.reader :as reader]
            [cljs.test :refer [deftest is testing]]
            [graph-merge.stage.review :as review]))

(def code-props
  (fn [lang] {:logseq.property.node/display-type :code :logseq.property.code/lang lang}))

(defn- page [opts]
  (review/graph-merge-page (merge {:graph-files [] :report [] :taken-titles #{}} opts)))

(deftest one-block-per-source-with-its-config-and-css
  (let [{:keys [blocks]} (page {:graph-files [["A" [{:file/path "logseq/config.edn" :file/content "{:a 1}"}
                                                    {:file/path "logseq/custom.css" :file/content "a {}"}]]
                                              ["B" [{:file/path "logseq/config.edn" :file/content "{:b 2}"}]]]})
        [a b] blocks]
    (is (= {:block/title "A"
            :build/children [{:block/title "{:a 1}" :build/properties (code-props "clojure")}
                             {:block/title "a {}" :build/properties (code-props "css")}]}
           a))
    (testing "a missing custom.css still gets its code block, empty"
      (is (= {:block/title "" :build/properties (code-props "css")}
             (second (:build/children b)))))))

(deftest merge-report-sections-are-readable-code-blocks
  (let [renames [{:graph "B" :title "status" :new-title "status (B)"}]
        {:keys [blocks]} (page {:report [["Property renames" renames] ["uuid re-keys" []]]})
        report (last blocks)
        [renames-block rekeys-block] (:build/children report)]
    (is (= "Merge report" (:block/title report)))
    (is (= "Property renames" (:block/title renames-block)))
    (testing "the section data is pretty-printed EDN that reads back unchanged"
      (let [code (first (:build/children renames-block))]
        (is (= (code-props "clojure") (:build/properties code)))
        (is (= renames (reader/read-string (:block/title code))))))
    (is (= [] (reader/read-string (-> rekeys-block :build/children first :block/title))))))

(deftest title-avoids-an-existing-graph-merge-page
  (is (= "Graph Merge" (get-in (page {}) [:page :block/title])))
  (is (= "Graph Merge (tool)" (get-in (page {:taken-titles #{"graph merge"}}) [:page :block/title]))))

(deftest page-uuid-is-deterministic-and-kept
  (let [p (:page (page {}))]
    (is (uuid? (:block/uuid p)))
    (is (= (:block/uuid p) (:block/uuid (:page (page {})))))
    (is (true? (:build/keep-uuid? p)))))
