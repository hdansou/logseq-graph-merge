(ns graph-merge.stage.normalize-test
  (:require [cljs.test :refer [deftest is testing]]
            [graph-merge.fixtures :as f]
            [graph-merge.stage.normalize :as normalize]
            [logseq.common.uuid :as common-uuid]
            [logseq.db.sqlite.export :as sqlite-export]))

(def apple-uuid #uuid "aaaaaaaa-0000-4000-8000-000000000001")
(def lib-uuid (common-uuid/gen-uuid :builtin-block-uuid "Library"))

(deftest graph-scoped-data-is-dropped-and-files-set-aside
  (let [files [{:file/path "logseq/config.edn" :file/content "{:a 1}"}]
        out (normalize/normalize-source
             (f/source "A" {:pages-and-blocks []
                            ::sqlite-export/kv-values [{:db/ident :logseq.kv/local-graph-uuid :kv/value "x"}]
                            ::sqlite-export/property-history #{{:block/uuid apple-uuid}}
                            ::sqlite-export/graph-files files}))]
    (testing "kv-values would copy the source graph's identity into the destination (trap 5)"
      (is (not (contains? (:export out) ::sqlite-export/kv-values))))
    (is (not (contains? (:export out) ::sqlite-export/property-history)))
    (testing "graph files leave the export but are kept for the Graph Merge review page (R12)"
      (is (not (contains? (:export out) ::sqlite-export/graph-files)))
      (is (= files (:graph-files out))))))

(deftest views-recycle-and-deleted-pages-are-skipped
  (let [out (normalize/normalize-source
             (f/source "A" {:pages-and-blocks
                            [(f/built-in-page "$$$views" :blocks [{:block/title "view"}])
                             (f/built-in-page "Recycle")
                             (f/page "Gone" (+ f/t0 1) :properties {:logseq.property/deleted-at f/t0})
                             (f/page "Apple" (+ f/t0 2))]}
                       :page-rows [(f/page-row "Apple" (+ f/t0 2) apple-uuid)]))]
    (is (= ["Apple"] (f/titles (:export out))))
    (is (= [{:graph "A" :title "$$$views" :reason :hidden-built-in}
            {:graph "A" :title "Recycle" :reason :hidden-built-in}
            {:graph "A" :title "Gone" :reason :deleted}]
           (:skipped out)))))

(deftest skipped-pages-report-their-uuid-when-exported-with-one
  (testing "S1b needs it to turn references to deleted pages into notes (R2b)"
    (let [gone #uuid "aaaaaaaa-0000-4000-8000-00000000dead"
          out (normalize/normalize-source
               (f/source "A" {:pages-and-blocks
                              [(assoc-in (f/page "Budget approval" f/t0 :properties {:logseq.property/deleted-at f/t0})
                                         [:page :block/uuid] gone)]}))]
      (is (= [{:graph "A" :title "Budget approval" :reason :deleted :uuid gone}] (:skipped out))))))

(deftest duplicate-built-in-pages-in-one-source-are-unioned
  (testing "Demo-Graph exports four $$$favorites pages (trap 10); blocks are kept in export order"
    (let [out (normalize/normalize-source
               (f/source "A" {:pages-and-blocks
                              [(f/built-in-page "$$$favorites" :blocks [{:block/title "fav 1"}])
                               (f/built-in-page "$$$favorites" :blocks [{:block/title "fav 2"}])]}))]
      (is (= ["$$$favorites"] (f/titles (:export out))))
      (is (= ["fav 1" "fav 2"]
             (->> out :export :pages-and-blocks first :blocks (mapv :block/title)))))))

(deftest page-identity-is-recovered
  (let [out (normalize/normalize-source
             (f/source "A" {:pages-and-blocks
                            [(f/built-in-page "Library")
                             (f/page "Apple" (+ f/t0 2))
                             {:page {:build/journal 20260910} :blocks []}]}
                       :page-rows [(f/page-row "Apple" (+ f/t0 2) apple-uuid
                                               :parent-uuid lib-uuid :order "a0")]))
        [library apple journal] (map :page (-> out :export :pages-and-blocks))]
    (testing "a regular page gets its source uuid back by (title, created-at), plus its source parent and order (R2a)"
      (is (= apple-uuid (:block/uuid apple)))
      (is (true? (:build/keep-uuid? apple)))
      (is (= lib-uuid (:graph-merge/parent-uuid apple)))
      (is (= "a0" (:graph-merge/order apple))))
    (testing "a built-in page uses Logseq's deterministic built-in uuid, so it lines up with the destination's"
      (is (= lib-uuid (:block/uuid library))))
    (testing "journals need no recovery; their uuid is derived from the day"
      (is (= {:build/journal 20260910} journal)))))

(deftest tag-and-property-page-entries-are-left-as-exported
  (testing "exported as {:block/uuid u} only, with no title (seen in Library-Test)"
    (let [class-page-uuid #uuid "00000002-1282-1814-5700-000000000000"
          out (normalize/normalize-source
               (f/source "A" {:pages-and-blocks [{:page {:block/uuid class-page-uuid}
                                                  :blocks [{:block/title "Order the new boat"}]}]}))]
      (testing "adding :build/keep-uuid? makes sqlite.build treat it as a new, title-less page and crash
                ('Cannot read properties of null (reading charAt)', found on real data)"
        (is (= {:block/uuid class-page-uuid}
               (-> out :export :pages-and-blocks first :page)))))))

(deftest titled-page-carrying-a-uuid-is-matched-by-uuid
  (let [u #uuid "aaaaaaaa-0000-4000-8000-0000000000ff"
        out (normalize/normalize-source
             (f/source "A" {:pages-and-blocks [(assoc-in (f/page "Apple" f/t0) [:page :block/uuid] u)]}
                       :page-rows [(f/page-row "Apple" (+ f/t0 999) u :parent-uuid lib-uuid)]))]
    (testing "the uuid wins over (title, created-at), and the row's parent is recorded"
      (is (= lib-uuid (-> out :export :pages-and-blocks first :page :graph-merge/parent-uuid))))))

(deftest unrecoverable-page-identity-aborts
  (testing "two identity rows for one (title, created-at)"
    (is (thrown-with-msg?
         js/Error #"ambiguous"
         (normalize/normalize-source
          (f/source "A" {:pages-and-blocks [(f/page "Apple" f/t0)]}
                    :page-rows [(f/page-row "Apple" f/t0 apple-uuid)
                                (f/page-row "Apple" f/t0 #uuid "aaaaaaaa-0000-4000-8000-000000000002")])))))
  (testing "no identity row for an exported page"
    (is (thrown-with-msg?
         js/Error #"unmatched"
         (normalize/normalize-source
          (f/source "A" {:pages-and-blocks [(f/page "Apple" f/t0)]}))))))

(deftest asset-uuids-are-recovered-by-checksum
  (testing "an unreferenced asset loses its uuid on export but keeps its checksum (trap 4)"
    (let [asset-uuid #uuid "bbbbbbbb-0000-4000-8000-000000000001"
          asset {:block/title "logo.png"
                 :build/tags #{:logseq.class/Asset}
                 :build/properties {:logseq.property.asset/type "png"
                                    :logseq.property.asset/checksum "c0ffee"}}
          out (normalize/normalize-source
               (f/source "A" {:pages-and-blocks
                              [(f/page "Apple" f/t0 :blocks [{:block/title "parent" :build/children [asset]}])]}
                         :page-rows [(f/page-row "Apple" f/t0 apple-uuid)]
                         :asset-rows [{:uuid asset-uuid :checksum "c0ffee" :type "png"}]))
          recovered (-> out :export :pages-and-blocks first :blocks first :build/children first)]
      (is (= asset-uuid (:block/uuid recovered)))
      (is (true? (:build/keep-uuid? recovered))))))
