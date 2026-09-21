(ns graph-merge.plan-test
  (:require [cljs.test :refer [deftest is testing]]
            [graph-merge.fixtures :as f]
            [graph-merge.plan :as plan]
            [logseq.common.uuid :as common-uuid]
            [logseq.db.sqlite.export :as sqlite-export]))

(deftest merging-no-sources-yields-a-valid-export-with-only-the-review-page
  (testing "the planner output is accepted by Logseq's own import validation"
    (let [merged (:export (plan/plan []))]
      (is (= ["Graph Merge"] (f/titles merged)))
      (is (nil? (:error (sqlite-export/validate-export merged)))))))

(deftest validation-gate-rejects-an-invalid-export
  (testing "guards the tests here: validate-export must actually catch bad input"
    (let [invalid {:pages-and-blocks
                   [{:page {:block/title "P"}
                     :blocks [{:block/title "b"
                               :build/properties {:user.property/undefined "x"}}]}]}]
      (is (some? (:error (sqlite-export/validate-export invalid)))))))

;; Two sources shaped like the real CRM-Simple / Library-Test exports.

(def library (common-uuid/gen-uuid :builtin-block-uuid "Library"))
(def todo-a #uuid "a0000000-0000-4000-8000-0000000000a1")
(def todo-b #uuid "b0000000-0000-4000-8000-0000000000b1")
(def done-b #uuid "b0000000-0000-4000-8000-0000000000b2")
(def acme-a #uuid "a0000000-0000-4000-8000-00000000ac01")
(def acme-b #uuid "b0000000-0000-4000-8000-00000000ac01")
(def work-a #uuid "a0000000-0000-4000-8000-000000000b01")
(def company-a #uuid "a0000000-0000-4000-8000-0000000c0a01")
(def gone-b #uuid "b0000000-0000-4000-8000-00000000dead")
(def chores-b #uuid "b0000000-0000-4000-8000-00000000c401")

(defn- source-a []
  (f/source
   "A"
   {:properties {:user.property/status-aaa {:block/title "status" :logseq.property/type :default
                                            :db/cardinality :db.cardinality/one
                                            :build/closed-values [{:value "todo" :uuid todo-a}]}}
    :classes {:user.class/company-aaa {:block/title "company" :block/uuid company-a :build/keep-uuid? true}}
    :pages-and-blocks
    [;; a tag page with blocks is exported as {:block/uuid u} only (seen in Library-Test)
     {:page {:block/uuid company-a} :blocks [{:block/title "A about companies"}]}
     {:page {:build/journal 20260910}
      :blocks [{:block/title "A standup" :build/properties {:user.property/status-aaa [:block/uuid todo-a]}}]}
     (assoc-in (f/page "Acme" (+ f/t0 1) :blocks [{:block/title "A notes"}])
               [:page :build/tags] #{:user.class/company-aaa})
     (f/page "Work" (+ f/t0 2) :blocks [{:block/title "A work"}])
     ;; Demo-Graph has a user page titled like the built-in #Card tag
     (f/page "Card" (+ f/t0 3) :blocks [{:block/title "A card notes"}])
     (f/built-in-page "Library")]
    ::sqlite-export/kv-values [{:db/ident :logseq.kv/local-graph-uuid :kv/value "a"}]
    ::sqlite-export/graph-files [{:file/path "logseq/config.edn" :file/content "{:a 1}"}]}
   :page-rows [(f/page-row "company" f/t0 company-a)
               (f/page-row "Acme" (+ f/t0 1) acme-a)
               (f/page-row "Work" (+ f/t0 2) work-a :parent-uuid library :order "a0")
               (f/page-row "Card" (+ f/t0 3) #uuid "a0000000-0000-4000-8000-00000000ca7d")]))

(defn- source-b []
  (f/source
   "B"
   {:properties {:user.property/duration-bbb {:block/title "Duration" :logseq.property/type :number
                                              :db/cardinality :db.cardinality/one}
                 :user.property/depends-bbb {:block/title "depends_on" :logseq.property/type :node
                                             :db/cardinality :db.cardinality/one}
                 :user.property/status-bbb {:block/title "Status" :logseq.property/type :default
                                            :db/cardinality :db.cardinality/one
                                            :build/closed-values [{:value "Todo" :uuid todo-b}
                                                                  {:value "done" :uuid done-b}]}}
    :classes {:user.class/company-bbb {:block/title "Company"}}
    :pages-and-blocks
    [;; values Logseq's importer rejects, shaped like GTD-02 and plugin-test (R2b)
     {:page {:block/title "Gone" :block/uuid gone-b :build/keep-uuid? true :block/created-at (+ f/t0 6)
             :build/properties {:logseq.property/deleted-at f/t0}}
      :blocks []}
     (f/page "Chores" (+ f/t0 7)
             :blocks [{:block/title "Pay water bill"
                       :build/properties {:user.property/duration-bbb :logseq.property/empty-placeholder
                                          :user.property/depends-bbb [:block/uuid gone-b]
                                          :logseq.property/status {:build/property-value :block :block/title "Icebox"}}}])
     {:page {:build/journal 20260910}
      :blocks [{:block/title "B standup" :build/properties {:user.property/status-bbb [:block/uuid todo-b]}}
               {:block/title (str "B see [[" acme-b "]]")
                :build/properties {:user.property/status-bbb [:block/uuid done-b]}}]}
     (assoc-in (f/page "Acme" (+ f/t0 5) :blocks [{:block/title "B notes"}])
               [:page :build/tags] #{:user.class/company-bbb})]}
   :page-rows [(f/page-row "Acme" (+ f/t0 5) acme-b)
               (f/page-row "Chores" (+ f/t0 7) chores-b)]))

(defn- entry [export pred]
  (some #(when (pred (:page %)) %) (:pages-and-blocks export)))

(deftest two-real-shaped-sources-merge-into-a-valid-deterministic-export
  (let [{:keys [export report]} (plan/plan [(source-a) (source-b)])
        journal (entry export :build/journal)
        acme (entry export #(= "Acme" (:block/title %)))
        work (entry export #(= "Work" (:block/title %)))]
    (testing "Logseq's own import validation accepts the merged export"
      (is (nil? (:error (sqlite-export/validate-export export)))))
    (testing "the journal holds both sources' blocks, on the canonical property and choices"
      (is (= ["A standup" "B standup" (str "B see [[" acme-a "]]")] (mapv :block/title (:blocks journal))))
      (is (= [[:block/uuid todo-a] [:block/uuid done-b]]
             (mapv #(get-in % [:build/properties :user.property/status-aaa]) (rest (:blocks journal))))))
    (testing "Acme is one page on the unified tag; B's ref to its Acme now points at A's"
      (is (= #{:user.class/company-aaa} (get-in acme [:page :build/tags])))
      (is (= ["A notes" "B notes"] (mapv :block/title (:blocks acme)))))
    (testing "the property and tag each exist once"
      (is (= [:user.property/status-aaa]
             (filter #{:user.property/status-aaa :user.property/status-bbb} (keys (:properties export)))))
      (is (= [:user.class/company-aaa] (keys (:classes export)))))
    (testing "the Library tree is restored"
      (is (= [:block/uuid library] (get-in work [:page :block/parent]))))
    (testing "report counts are real block counts, including nested blocks (used by the D5 post-check)"
      (is (= {"A" {:pages 6 :blocks 5} "B" {:pages 3 :blocks 6}} (get-in report [:counts :sources])))
      (is (= 11 (get-in report [:counts :merged :blocks]))))
    (testing "rejected values are fixed and reported, with their information kept as notes (R2b)"
      (let [chores (entry export #(= "Chores" (:block/title %)))]
        (is (= ["Status: Icebox" "depends_on: Gone (deleted page)"]
               (mapv :block/title (-> chores :blocks first :build/children)))))
      (is (= [:deleted-page-ref :dropped-empty-value :invalid-choice-note]
             (sort-by name (map :fix (:value-fixes report))))))
    (testing "graph identity never leaks (trap 5)"
      (is (not (contains? export ::sqlite-export/kv-values))))
    (testing "the review page and report are there"
      (is (some? (entry export #(= "Graph Merge" (:block/title %)))))
      (is (= [{:title "Acme" :uuid acme-a :members [{:graph "A" :title "Acme"} {:graph "B" :title "Acme"}]}]
             (:page-merges report))))
    (testing "D1: the same inputs give byte-identical output"
      (is (= (pr-str (plan/plan [(source-a) (source-b)]))
             (pr-str (plan/plan [(source-a) (source-b)])))))))

(deftest config-from-copies-one-source-graph-files
  (let [{:keys [export]} (plan/plan [(source-a) (source-b)] {:config-from "A"})]
    (is (= [{:file/path "logseq/config.edn" :file/content "{:a 1}"}]
           (::sqlite-export/graph-files export)))))
