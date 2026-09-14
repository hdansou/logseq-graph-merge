(ns graph-merge.stage.idents-test
  (:require [cljs.test :refer [deftest is testing]]
            [graph-merge.stage.idents :as idents]))

(def maps
  {:property-ident-maps {"B" {:user.property/status-b :user.property/status-a}}
   :class-ident-maps {"B" {:user.class/company-b :user.class/company-a}}})

(defn- block-of [source]
  (-> source :export :pages-and-blocks first :blocks first))

(deftest later-source-idents-are-rewritten-in-pages-and-blocks
  (let [block {:block/title "Acme"
               :build/tags #{:user.class/company-b}
               :build/properties {:user.property/status-b "open"
                                  :logseq.property.table/hidden-columns [:user.property/status-b :block/created-at]
                                  :logseq.property.table/sorting [{:id :user.property/status-b :asc? true}]}}
        sources [{:graph "A" :export {:pages-and-blocks [{:page {:block/title "P"} :blocks [block]}]}}
                 {:graph "B" :export {:properties {:user.property/status-b {}}
                                      :classes {:user.class/company-b {}}
                                      :pages-and-blocks [{:page {:block/title "P"} :blocks [block]}]}}]
        {[a b] :sources} (idents/rewrite-idents (assoc maps :sources sources :properties {} :classes {}))]
    (testing "the first source has no mappings, so it is untouched"
      (is (= block (block-of a))))
    (testing "property keys, tags and view settings use the canonical idents"
      (is (= {:block/title "Acme"
              :build/tags #{:user.class/company-a}
              :build/properties {:user.property/status-a "open"
                                 :logseq.property.table/hidden-columns [:user.property/status-a :block/created-at]
                                 :logseq.property.table/sorting [{:id :user.property/status-a :asc? true}]}}
             (block-of b))))
    (testing "the source's own ontology is dropped; S3/S4 produced the merged one"
      (is (= #{:pages-and-blocks} (set (keys (:export b))))))))

(deftest merged-definitions-are-rewritten-with-their-origin-graph-maps
  (let [{:keys [properties classes]}
        (idents/rewrite-idents
         (assoc maps
                :sources []
                :properties {:user.property/employer {:graph-merge/graph "B"
                                                      :build/property-classes #{:user.class/company-b}}}
                :classes {:user.class/company-a {:graph-merge/graph "A"
                                                 :build/properties {:user.property/status-b "kept: A has no map"}}}))]
    (is (= #{:user.class/company-a} (get-in properties [:user.property/employer :build/property-classes])))
    (is (= {:user.property/status-b "kept: A has no map"} (get-in classes [:user.class/company-a :build/properties])))))

(deftest idents-inside-strings-are-reported-not-rewritten
  (let [query "{:query [:find ?b :where [?b :user.property/status-b]]}"
        {[b] :sources flagged :strings-with-idents}
        (idents/rewrite-idents
         (assoc maps :properties {} :classes {}
                :sources [{:graph "B" :export {:pages-and-blocks
                                                [{:page {:block/title "P"}
                                                  :blocks [{:block/title "q" :build/properties {:logseq.property/query query}}]}]}}]))]
    (is (= query (get-in (block-of b) [:build/properties :logseq.property/query])))
    (is (= [{:graph "B" :ident :user.property/status-b}] flagged))))
