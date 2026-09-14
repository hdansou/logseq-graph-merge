(ns graph-merge.stage.ontology-test
  (:require [cljs.test :refer [deftest is testing]]
            [graph-merge.stage.ontology :as ontology]
            [logseq.common.uuid :as common-uuid]))

(defn- src [graph properties]
  {:graph graph :export {:properties properties :pages-and-blocks []}})

(defn- prop [title type & {:as more}]
  (merge {:block/title title :logseq.property/type type :db/cardinality :db.cardinality/one} more))

(defn- class-src [graph classes]
  {:graph graph :export {:classes classes :pages-and-blocks []}})

(deftest compatible-same-title-properties-unify-on-the-first-ident
  (testing "idents differ by a random suffix in every graph (trap 2), so matching is by title"
    (let [{:keys [properties ident-maps renames]}
          (ontology/unify-properties
           [(src "A" {:user.property/status-aaa (prop "Status" :default)})
            (src "B" {:user.property/status-bbb (prop " status " :default)})])]
      (is (= [:user.property/status-aaa] (keys properties)))
      (is (= "Status" (get-in properties [:user.property/status-aaa :block/title])))
      (is (= {"B" {:user.property/status-bbb :user.property/status-aaa}} ident-maps))
      (is (= [] renames))
      (testing "the merged definition remembers its origin graph, whose idents its own refs use (S5)"
        (is (= "A" (get-in properties [:user.property/status-aaa :graph-merge/graph])))))))

(deftest missing-cardinality-counts-as-one
  (let [{:keys [ident-maps]}
        (ontology/unify-properties
         [(src "A" {:user.property/p-a (prop "p" :default)})
          (src "B" {:user.property/p-b {:block/title "p" :logseq.property/type :default}})])]
    (is (= {"B" {:user.property/p-b :user.property/p-a}} ident-maps))))

(deftest incompatible-property-is-renamed-for-the-later-graph
  (let [{:keys [properties ident-maps renames]}
        (ontology/unify-properties
         [(src "A" {:user.property/status-aaa (prop "status" :default)})
          (src "Graph B" {:user.property/status-bbb (prop "status" :number)})])]
    (is (= "status (Graph B)" (get-in properties [:user.property/status-GraphB :block/title])))
    (is (= :number (get-in properties [:user.property/status-GraphB :logseq.property/type])))
    (is (= "Graph B" (get-in properties [:user.property/status-GraphB :graph-merge/graph])))
    (is (= {"Graph B" {:user.property/status-bbb :user.property/status-GraphB}} ident-maps))
    (is (= [{:graph "Graph B" :title "status" :ident :user.property/status-bbb
             :new-ident :user.property/status-GraphB :new-title "status (Graph B)"
             :canonical {:logseq.property/type :default :db/cardinality :db.cardinality/one}
             :actual {:logseq.property/type :number :db/cardinality :db.cardinality/one}}]
           renames))))

(deftest closed-values-are-unioned-by-value
  (let [todo-a #uuid "a0000000-0000-4000-8000-00000000000a"
        doing-a #uuid "a0000000-0000-4000-8000-00000000000b"
        doing-b #uuid "b0000000-0000-4000-8000-00000000000b"
        done-b #uuid "b0000000-0000-4000-8000-00000000000c"
        {:keys [properties uuid-map]}
        (ontology/unify-properties
         [(src "A" {:user.property/s-a (prop "s" :default :build/closed-values [{:value "Todo" :uuid todo-a}
                                                                               {:value "Doing" :uuid doing-a}])})
          (src "B" {:user.property/s-b (prop "s" :default :build/closed-values [{:value "doing" :uuid doing-b}
                                                                               {:value "Done" :uuid done-b}])})])]
    (is (= [{:value "Todo" :uuid todo-a} {:value "Doing" :uuid doing-a} {:value "Done" :uuid done-b}]
           (get-in properties [:user.property/s-a :build/closed-values])))
    (testing "blocks in B that point at B's 'doing' choice must point at A's after the merge"
      (is (= {doing-b doing-a} uuid-map)))))

(deftest blank-closed-values-never-merge
  (testing "Bafigo's Effort property has several closed values with an empty :value"
    (let [u1 #uuid "a0000000-0000-4000-8000-000000000001"
          u2 #uuid "b0000000-0000-4000-8000-000000000002"
          {:keys [properties uuid-map]}
          (ontology/unify-properties
           [(src "A" {:user.property/e-a (prop "Effort" :default :build/closed-values [{:value "" :uuid u1}])})
            (src "B" {:user.property/e-b (prop "Effort" :default :build/closed-values [{:value "" :uuid u2}])})])]
      (is (= [{:value "" :uuid u1} {:value "" :uuid u2}]
             (get-in properties [:user.property/e-a :build/closed-values])))
      (is (= {} uuid-map)))))

(deftest every-merged-definition-gets-a-kept-uuid-and-others-map-to-it
  (testing "tag/property pages are exported as {:block/uuid u}; later graphs' pages must find the merged definition"
    (let [u-a #uuid "a0000000-0000-4000-8000-0000000000aa"
          u-b #uuid "b0000000-0000-4000-8000-0000000000bb"
          props (ontology/unify-properties
                 [(src "A" {:user.property/p-a (prop "p" :default)})
                  (src "B" {:user.property/p-b (prop "p" :default :block/uuid u-b :build/keep-uuid? true)})])
          classes (ontology/unify-classes
                   [(class-src "A" {:user.class/c-a {:block/title "c" :block/uuid u-a :build/keep-uuid? true}})
                    (class-src "B" {:user.class/c-b {:block/title "c" :block/uuid u-b :build/keep-uuid? true}})]
                   {})]
      (testing "canonical had no uuid: the first member that has one lends it"
        (is (= u-b (get-in props [:properties :user.property/p-a :block/uuid])))
        (is (true? (get-in props [:properties :user.property/p-a :build/keep-uuid?])))
        (is (= {} (:uuid-map props))))
      (testing "canonical has a uuid: later members' uuids map to it"
        (is (= u-a (get-in classes [:classes :user.class/c-a :block/uuid])))
        (is (= {u-b u-a} (:uuid-map classes))))))
  (testing "nobody has a uuid: fall back to the ident-derived uuid build would use"
    (let [{:keys [properties]} (ontology/unify-properties [(src "A" {:user.property/p-a (prop "p" :default)})])]
      (is (= (common-uuid/gen-uuid :db-ident-block-uuid :user.property/p-a)
             (get-in properties [:user.property/p-a :block/uuid]))))))

(deftest same-title-classes-unify-and-union-their-properties-and-parents
  (testing "shapes follow CRM-Simple/Bafigo: company in both, Bafigo's vendor extends company"
    (let [{:keys [classes ident-maps]}
          (ontology/unify-classes
           [(class-src "A" {:user.class/company-a {:block/title "company"
                                                   :build/class-properties [:user.property/website-a]}})
            (class-src "B" {:user.class/company-b {:block/title "Company"
                                                   :build/class-properties [:user.property/url-b :user.property/website-b]}
                            :user.class/vendor-b {:block/title "vendor"
                                                  :build/class-extends #{:user.class/company-b :logseq.class/Task}}})]
           {"B" {:user.property/website-b :user.property/website-a}})]
      (is (= {"B" {:user.class/company-b :user.class/company-a}} ident-maps))
      (is (= "A" (get-in classes [:user.class/company-a :graph-merge/graph])))
      (is (= "B" (get-in classes [:user.class/vendor-b :graph-merge/graph])))
      (testing "properties: first source's order, then new ones, through the S3 property map, no duplicates"
        (is (= [:user.property/website-a :user.property/url-b]
               (get-in classes [:user.class/company-a :build/class-properties]))))
      (testing "parents are mapped to canonical class idents; built-in parents pass through"
        (is (= #{:user.class/company-a :logseq.class/Task}
               (get-in classes [:user.class/vendor-b :build/class-extends])))))))

(deftest extends-union-that-creates-a-cycle-falls-back-to-the-canonical-parents
  (let [{:keys [classes cycles]}
        (ontology/unify-classes
         [(class-src "A" {:user.class/x-a {:block/title "x" :build/class-extends #{:user.class/y-a}}
                          :user.class/y-a {:block/title "y"}})
          (class-src "B" {:user.class/x-b {:block/title "x"}
                          :user.class/y-b {:block/title "y" :build/class-extends #{:user.class/x-b}}})]
         {})]
    (is (= #{:user.class/y-a} (get-in classes [:user.class/x-a :build/class-extends])))
    (is (nil? (get-in classes [:user.class/y-a :build/class-extends])))
    (is (= [{:kind :extends-cycle :title "y" :ident :user.class/y-a
             :rejected #{:user.class/x-a}}]
           cycles))))

(deftest class-differences-keep-the-first-and-are-reported
  (let [{:keys [classes diffs]}
        (ontology/unify-classes
         [(class-src "A" {:user.class/c-a {:block/title "c" :build/properties {:logseq.property/icon {:id "A"}}}})
          (class-src "B" {:user.class/c-b {:block/title "c" :build/properties {:logseq.property/icon {:id "B"}}}})]
         {})]
    (is (= {:logseq.property/icon {:id "A"}} (get-in classes [:user.class/c-a :build/properties])))
    (is (= [{:kind :class :graph "B" :title "c" :attrs [:build/properties]}] diffs))))

(deftest other-schema-differences-keep-the-first-and-are-reported
  (let [{:keys [properties diffs]}
        (ontology/unify-properties
         [(src "A" {:user.property/p-a (prop "p" :default :logseq.property/hide? true
                                             :block/created-at 1)})
          (src "B" {:user.property/p-b (prop "p" :default :logseq.property/hide? false
                                             :block/created-at 2)})])]
    (is (true? (get-in properties [:user.property/p-a :logseq.property/hide?])))
    (testing "timestamps are not schema, so they are not reported"
      (is (= [{:kind :property :graph "B" :title "p" :attrs [:logseq.property/hide?]}] diffs)))))
