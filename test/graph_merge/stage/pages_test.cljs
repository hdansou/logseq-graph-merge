(ns graph-merge.stage.pages-test
  (:require [cljs.test :refer [deftest is testing]]
            [graph-merge.fixtures :as f]
            [graph-merge.stage.pages :as pages]
            [graph-merge.uuid :as guuid]
            [logseq.common.uuid :as common-uuid]))

(defn- src [graph & entries]
  {:graph graph :export {:pages-and-blocks (vec entries)}})

(defn- merge-pages [sources & {:as opts}]
  (pages/merge-pages (merge {:sources sources :classes {} :uuid-map {}} opts)))

(defn- block-titles [entry]
  (mapv :block/title (:blocks entry)))

(deftest journals-merge-by-day-in-source-order
  (let [{:keys [pages-and-blocks]}
        (merge-pages [(src "A" {:page {:build/journal 20260910 :build/properties {:user.property/mood "A"}}
                                :blocks [{:block/title "A1"}]})
                      (src "B" {:page {:build/journal 20260910 :build/properties {:user.property/mood "B"
                                                                                  :user.property/weather "sun"}}
                                :blocks [{:block/title "B1"}]}
                           {:page {:build/journal 20260911} :blocks [{:block/title "B2"}]})])
        [sept-10 sept-11] pages-and-blocks]
    (is (= 2 (count pages-and-blocks)))
    (is (= ["A1" "B1"] (block-titles sept-10)))
    (testing "page properties: the first source wins, new keys are added"
      (is (= {:user.property/mood "A" :user.property/weather "sun"}
             (get-in sept-10 [:page :build/properties]))))
    (is (= ["B2"] (block-titles sept-11)))))

(deftest built-in-pages-merge-by-title
  (let [contents-a (f/built-in-page "Contents" :blocks [{:block/title "A toc"}])
        contents-b (f/built-in-page "Contents" :blocks [{:block/title "B toc"}])
        {:keys [pages-and-blocks]} (merge-pages [(src "A" contents-a) (src "B" contents-b)])]
    (is (= [["Contents" ["A toc" "B toc"]]]
           (map (juxt (comp :block/title :page) block-titles) pages-and-blocks)))))

(deftest favorites-are-deduplicated-by-link-target
  (let [target #uuid "a0000000-0000-4000-8000-000000000001"
        other #uuid "b0000000-0000-4000-8000-000000000002"
        fav (fn [u] {:block/title "" :block/link [:block/uuid u]})
        {[favorites] :pages-and-blocks :keys [favorite-dedupes]}
        (merge-pages [(src "A" (f/built-in-page "$$$favorites" :blocks [(fav target)]))
                      (src "B" (f/built-in-page "$$$favorites" :blocks [(fav target) (fav other)]))])]
    (is (= [(fav target) (fav other)] (:blocks favorites)))
    (testing "dropped duplicates are reported, so block counts reconcile (real case: Library-Test and another source both favorite Library)"
      (is (= [[:block/uuid target]] favorite-dedupes)))))

(deftest tag-and-property-page-entries-follow-the-merged-definition
  (testing "exported as {:block/uuid u}; S3/S4 mapped B's definition uuid to A's"
    (let [class-a #uuid "a0000000-0000-4000-8000-0000000000c1"
          class-b #uuid "b0000000-0000-4000-8000-0000000000c1"
          {:keys [pages-and-blocks]}
          (merge-pages [(src "A" {:page {:block/uuid class-a} :blocks [{:block/title "A about"}]})
                        (src "B" {:page {:block/uuid class-b} :blocks [{:block/title "B about"}]})]
                       :uuid-map {class-b class-a})]
      (is (= [[class-a ["A about" "B about"]]]
             (map (juxt (comp :block/uuid :page) block-titles) pages-and-blocks))))))

;; --- S7d regular pages: the examples table in docs/merge-workflow.md §4 ---

(defn- uuid-for [graph title]
  (guuid/derived-uuid graph title))

(defn- pg [graph title & {:keys [tags parent]}]
  {:page (cond-> {:block/title title :block/uuid (uuid-for graph title) :build/keep-uuid? true}
           (seq tags) (assoc :build/tags (set tags))
           parent (assoc :graph-merge/parent-uuid parent))
   :blocks [{:block/title (str graph " " title)}]})

(defn- regular-pages [result]
  (->> (:pages-and-blocks result)
       (map (fn [{:keys [page] :as entry}]
              [(:block/title page) (set (:build/tags page)) (block-titles entry)]))
       set))

(deftest examples-table
  (testing "Apple (G1), Apple (G2): 1 page, G1 blocks then G2 blocks"
    (let [result (merge-pages [(src "G1" (pg "G1" "Apple")) (src "G2" (pg "G2" "Apple"))])]
      (is (= #{["Apple" #{} ["G1 Apple" "G2 Apple"]]} (regular-pages result)))
      (is (= {(uuid-for "G2" "Apple") (uuid-for "G1" "Apple")} (:uuid-map result)))))
  (testing "Apple #Fruit (G1), Apple (G2): one tagged cluster, so the untagged page joins it"
    (is (= #{["Apple" #{:Fruit} ["G1 Apple" "G2 Apple"]]}
           (regular-pages (merge-pages [(src "G1" (pg "G1" "Apple" :tags [:Fruit])) (src "G2" (pg "G2" "Apple"))])))))
  (testing "Apple (G1), Apple #Fruit (G2): same result, order doesn't matter; the earliest member lends uuid and title"
    (let [result (merge-pages [(src "G1" (pg "G1" "Apple")) (src "G2" (pg "G2" "Apple" :tags [:Fruit]))])]
      (is (= #{["Apple" #{:Fruit} ["G1 Apple" "G2 Apple"]]} (regular-pages result)))
      (is (= (uuid-for "G1" "Apple") (-> result :pages-and-blocks first :page :block/uuid)))))
  (testing "Apple #Company (G1), Apple #Fruit (G2): 2 pages, kept separate"
    (is (= #{["Apple" #{:Company} ["G1 Apple"]] ["Apple" #{:Fruit} ["G2 Apple"]]}
           (regular-pages (merge-pages [(src "G1" (pg "G1" "Apple" :tags [:Company]))
                                        (src "G2" (pg "G2" "Apple" :tags [:Fruit]))])))))
  (testing "Apple #Company, Apple #Fruit, Apple: 3 pages, the untagged page stays on its own"
    (is (= #{["Apple" #{:Company} ["G1 Apple"]] ["Apple" #{:Fruit} ["G2 Apple"]] ["Apple" #{} ["G3 Apple"]]}
           (regular-pages (merge-pages [(src "G1" (pg "G1" "Apple" :tags [:Company]))
                                        (src "G2" (pg "G2" "Apple" :tags [:Fruit]))
                                        (src "G3" (pg "G3" "Apple"))])))))
  (testing "Apple #Fruit (G1), Apple #Fruit #Food (G2): 1 page with both tags"
    (is (= #{["Apple" #{:Fruit :Food} ["G1 Apple" "G2 Apple"]]}
           (regular-pages (merge-pages [(src "G1" (pg "G1" "Apple" :tags [:Fruit]))
                                        (src "G2" (pg "G2" "Apple" :tags [:Fruit :Food]))]))))))

(deftest tag-overlap-is-transitive
  (testing "#Fruit and #Food share nothing, but both overlap #Fruit #Food, so all three merge"
    (is (= #{["Apple" #{:Fruit :Food} ["G1 Apple" "G2 Apple" "G3 Apple"]]}
           (regular-pages (merge-pages [(src "G1" (pg "G1" "Apple" :tags [:Fruit]))
                                        (src "G2" (pg "G2" "Apple" :tags [:Food]))
                                        (src "G3" (pg "G3" "Apple" :tags [:Fruit :Food]))]))))))

(deftest titles-match-case-and-space-insensitively
  (is (= #{["Apple" #{} ["G1 Apple" "G2  apple "]]}
         (regular-pages (merge-pages [(src "G1" (pg "G1" "Apple")) (src "G2" (pg "G2" " apple "))])))))

(deftest same-title-under-different-parents-stays-separate
  (testing "Library-Test has 12 pages named ii under different chapters"
    (let [result (merge-pages [(src "G1" (pg "G1" "Chap1") (pg "G1" "ii" :parent (uuid-for "G1" "Chap1")))
                               (src "G2" (pg "G2" "Chap2") (pg "G2" "ii" :parent (uuid-for "G2" "Chap2")))])]
      (is (= 2 (count (filter #(= "ii" (get-in % [:page :block/title])) (:pages-and-blocks result))))))))

;; --- S7e class vs page (R4) ---

(deftest top-level-page-with-a-class-title-joins-the-class-page
  (let [class-uuid #uuid "a0000000-0000-4000-8000-0000000000c1"
        classes {:user.class/company-a {:block/title "company" :block/uuid class-uuid}}
        result (merge-pages [(src "A" (pg "A" "Company"))
                             (src "B" {:page {:block/uuid class-uuid} :blocks [{:block/title "B about"}]})]
                            :classes classes)]
    (is (= [[class-uuid ["A Company" "B about"]]]
           (map (juxt (comp :block/uuid :page) block-titles) (:pages-and-blocks result))))
    (is (= {(uuid-for "A" "Company") class-uuid} (:uuid-map result)))
    (is (= [{:graph "A" :title "Company" :class-uuid class-uuid}] (:definition-page-merges result)))))

(deftest nested-page-with-a-class-title-stays-a-page
  (let [classes {:user.class/company-a {:block/title "company" :block/uuid #uuid "a0000000-0000-4000-8000-0000000000c1"}}
        result (merge-pages [(src "A" (pg "A" "Clients") (pg "A" "Company" :parent (uuid-for "A" "Clients")))]
                            :classes classes)]
    (is (= #{"Clients" "Company"} (set (map (comp :block/title :page) (:pages-and-blocks result)))))))

;; --- S7f page tree (R6b) ---

(defn- page-named [result title]
  (some #(when (= title (get-in % [:page :block/title])) (:page %)) (:pages-and-blocks result)))

(deftest page-parents-are-restored-and-helper-keys-removed
  (let [result (merge-pages [(src "A" (pg "A" "Chap1") (pg "A" "ii" :parent (uuid-for "A" "Chap1")))])
        ii (page-named result "ii")]
    (is (= [:block/uuid (uuid-for "A" "Chap1")] (:block/parent ii)))
    (is (string? (:block/order ii)))
    (is (empty? (filter #(= "graph-merge" (namespace %)) (keys ii))))
    (testing "top-level pages get no parent and no order"
      (is (nil? (:block/parent (page-named result "Chap1"))))
      (is (nil? (:block/order (page-named result "Chap1")))))))

(deftest a-child-follows-its-parent-into-the-merged-page
  (let [result (merge-pages [(src "A" (pg "A" "Company"))
                             (src "B" (pg "B" "Company") (pg "B" "Microsoft" :parent (uuid-for "B" "Company")))])]
    (is (= [:block/uuid (uuid-for "A" "Company")] (:block/parent (page-named result "Microsoft"))))))

(deftest library-children-keep-the-built-in-parent
  (testing "Library's uuid is shared by design, so the parent is valid even without a Library entry"
    (let [library #uuid "00000004-1294-7765-6000-000000000000"
          result (merge-pages [(src "A" (pg "A" "Work" :parent library))])]
      (is (= [:block/uuid library] (:block/parent (page-named result "Work")))))))

(deftest a-page-whose-parent-is-gone-is-re-rooted-and-reported
  (let [gone #uuid "a0000000-0000-4000-8000-00000000dead"
        result (merge-pages [(src "A" (pg "A" "Orphan" :parent gone))])]
    (is (nil? (:block/parent (page-named result "Orphan"))))
    (is (= [{:title "Orphan" :missing-parent gone}] (:re-rooted result)))))

(deftest sibling-orders-follow-source-order-then-source-sibling-order
  (let [chap (uuid-for "A" "Book")
        child (fn [graph title order]
                (assoc-in (pg graph title :parent chap) [:page :graph-merge/order] order))
        result (merge-pages [(src "A" (pg "A" "Book") (child "A" "Two" "a1") (child "A" "One" "a0"))
                             (src "B" (child "B" "Three" "a0"))])
        order-of #(:block/order (page-named result %))]
    (is (neg? (compare (order-of "One") (order-of "Two"))))
    (is (neg? (compare (order-of "Two") (order-of "Three"))))))

(deftest merged-pages-are-reported
  (let [result (merge-pages [(src "A" (pg "A" "Apple")) (src "B" (pg "B" "apple"))])]
    (is (= [{:title "Apple" :uuid (uuid-for "A" "Apple") :members [{:graph "A" :title "Apple"} {:graph "B" :title "apple"}]}]
           (:page-merges result)))))

(deftest page-titled-like-a-built-in-tag-or-property-joins-its-page
  (testing "Demo-Graph has a page 'Card'; import matches titles exactly (get-case-page) and corrupts the built-in #Card"
    (let [card (common-uuid/gen-uuid :db-ident-block-uuid :logseq.class/Card)
          status (common-uuid/gen-uuid :db-ident-block-uuid :logseq.property/status)
          result (merge-pages [(src "A" (pg "A" "Card") (pg "A" "Status") (pg "A" "card notes"))])]
      (is (= {card ["A Card"] status ["A Status"]}
             (into {} (keep (fn [{:keys [page] :as e}] (when-not (:block/title page) [(:block/uuid page) (block-titles e)])))
                   (:pages-and-blocks result))))
      (testing "only exact titles collide on import, so 'card notes' stays a page"
        (is (some #(= "card notes" (get-in % [:page :block/title])) (:pages-and-blocks result)))))))

(deftest pages-with-blank-titles-never-merge
  (testing "the 5-graph dry run merged 7 unrelated untitled pages into one; a blank title identifies nothing"
    (let [result (merge-pages [(src "A" (pg "A" "") (assoc-in (pg "A" " ") [:page :block/uuid] (uuid-for "A" "blank-2")))
                               (src "B" (pg "B" ""))])]
      (is (= 3 (count (:pages-and-blocks result))))
      (is (= {} (:uuid-map result)))
      (is (= [] (:page-merges result))))))
