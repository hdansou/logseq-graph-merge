(ns graph-merge.stage.values-test
  (:require [cljs.test :refer [deftest is testing]]
            [graph-merge.stage.values :as values]))

(def gone #uuid "6a8ce96c-0000-4000-8000-00000000dead")
(def live #uuid "6a8ce96c-0000-4000-8000-0000000011fe")

;; Shapes copied from the GTD-02 and plugin-test exports.
(def properties
  {:user.property/Duration-TWXFW8GR {:block/title "Duration" :logseq.property/type :number}
   :user.property/Note-x {:block/title "Note" :logseq.property/type :default}
   :user.property/depends_on-SfjMwya6 {:block/title "depends_on" :logseq.property/type :node}
   :user.property/blocked_by-x {:block/title "blocked_by" :logseq.property/type :node
                                :db/cardinality :db.cardinality/many}})

(defn- clean [blocks & {:keys [page]}]
  (values/clean-values {:graph "G"
                        :export {:properties properties
                                 :pages-and-blocks [{:page (or page {:block/title "P"}) :blocks blocks}]}
                        :skipped [{:graph "G" :title "Budget approval" :reason :deleted :uuid gone}]}))

(defn- first-block [result]
  (-> result :export :pages-and-blocks first :blocks first))

(deftest empty-number-values-are-dropped
  (testing "GTD-02: Duration (:number) = :logseq.property/empty-placeholder; the importer rejects it"
    (let [result (clean [{:block/title "Pay water bill"
                          :build/properties {:user.property/Duration-TWXFW8GR :logseq.property/empty-placeholder
                                             :user.property/Note-x :logseq.property/empty-placeholder}}])]
      (is (= {:user.property/Note-x :logseq.property/empty-placeholder}
             (:build/properties (first-block result)))
          "only :number properties are affected")
      (is (= [{:graph "G" :node "Pay water bill" :property :user.property/Duration-TWXFW8GR :fix :dropped-empty-value}]
             (:value-fixes result))))))

(deftest invalid-built-in-choice-becomes-a-note
  (testing "GTD-02: Status set to a free-text block 'Icebox' instead of one of its choices"
    (let [result (clean [{:block/title "Learn to Drive a Car"
                          :build/children [{:block/title "existing child"}]
                          :build/properties {:logseq.property/status {:build/property-value :block
                                                                      :block/title "Icebox"
                                                                      :block/uuid #uuid "68c4cd09-0da9-4de0-8290-f96f634e84ad"
                                                                      :build/keep-uuid? true}
                                             :logseq.property/priority :logseq.property/priority.high}}])
          block (first-block result)]
      (is (= {:logseq.property/priority :logseq.property/priority.high} (:build/properties block))
          "valid choices are untouched")
      (is (= [{:block/title "existing child"} {:block/title "Status: Icebox"}] (:build/children block)))
      (is (= [{:graph "G" :node "Learn to Drive a Car" :property :logseq.property/status
               :fix :invalid-choice-note :value "Icebox"}]
             (:value-fixes result))))))

(deftest references-to-deleted-pages-become-notes
  (testing "plugin-test: depends_on points at the deleted page 'Budget approval'"
    (let [result (clean [{:block/title "Adopt the new wiki"
                          :build/properties {:user.property/depends_on-SfjMwya6 [:block/uuid gone]
                                             :user.property/blocked_by-x #{[:block/uuid gone] [:block/uuid live]}}}])
          block (first-block result)]
      (testing "a many-valued property keeps its live values"
        (is (= {:user.property/blocked_by-x #{[:block/uuid live]}} (:build/properties block))))
      (testing "notes follow sorted property order, so output is deterministic (D1)"
        (is (= [{:block/title "blocked_by: Budget approval (deleted page)"}
                {:block/title "depends_on: Budget approval (deleted page)"}]
               (:build/children block))))
      (is (= #{:deleted-page-ref} (set (map :fix (:value-fixes result))))))))

(deftest text-links-to-deleted-pages-become-plain-titles
  (let [result (clean [{:block/title (str "see [[" gone "]] and #[[" gone "]] but keep [[" live "]]")}])]
    (is (= (str "see Budget approval and Budget approval but keep [[" live "]]")
           (:block/title (first-block result))))))

(deftest page-properties-get-the-same-treatment-with-notes-as-blocks
  (let [result (clean [{:block/title "b"}]
                      :page {:block/title "P" :build/properties {:user.property/depends_on-SfjMwya6 [:block/uuid gone]}})
        entry (-> result :export :pages-and-blocks first)]
    (is (nil? (get-in entry [:page :build/properties :user.property/depends_on-SfjMwya6])))
    (is (= ["b" "depends_on: Budget approval (deleted page)"] (mapv :block/title (:blocks entry))))))
