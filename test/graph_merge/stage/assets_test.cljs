(ns graph-merge.stage.assets-test
  (:require [cljs.test :refer [deftest is testing]]
            [graph-merge.stage.assets :as assets]))

(def logo-a #uuid "a0000000-0000-4000-8000-00000000000a")
(def logo-b #uuid "b0000000-0000-4000-8000-00000000000b")
(def chart-b #uuid "b0000000-0000-4000-8000-00000000000c")

(defn- asset [uuid title checksum & {:keys [children]}]
  (cond-> {:block/uuid uuid
           :block/title title
           :build/keep-uuid? true
           :build/tags #{:logseq.class/Asset}
           :build/properties {:logseq.property.asset/type "png"
                              :logseq.property.asset/checksum checksum}}
    children (assoc :build/children children)))

(defn- src [graph & blocks]
  {:graph graph :export {:pages-and-blocks [{:page {:block/title "P"} :blocks (vec blocks)}]}})

(defn- blocks-of [source]
  (-> source :export :pages-and-blocks first :blocks))

(deftest first-asset-per-checksum-is-kept-and-listed-for-copying
  (let [{:keys [sources files]}
        (assets/dedupe-assets [(src "A" (asset logo-a "logo.png" "c1"))
                               (src "B" {:block/title "parent" :build/children [(asset chart-b "chart.png" "c2")]})])]
    (is (= [{:graph "A" :uuid logo-a :type "png" :checksum "c1"}
            {:graph "B" :uuid chart-b :type "png" :checksum "c2"}]
           files))
    (is (= "logo.png" (-> sources first blocks-of first :block/title)))))

(deftest duplicate-asset-becomes-a-reference-block-that-keeps-its-children
  (let [note {:block/title "annotation"}
        {[_ b] :sources :keys [files uuid-map dedupes]}
        (assets/dedupe-assets [(src "A" (asset logo-a "logo.png" "c1"))
                               (src "B" (asset logo-b "logo copy.png" "c1" :children [note]))])]
    (testing "the same file is copied once"
      (is (= [logo-a] (map :uuid files))))
    (testing "B's page keeps a visible pointer in the asset's place, with its children"
      (is (= {:block/title (str "[[" logo-a "]]") :build/children [note]}
             (first (blocks-of b)))))
    (testing "other refs to B's asset follow through S8"
      (is (= {logo-b logo-a} uuid-map)))
    (is (= [{:graph "B" :title "logo copy.png" :uuid logo-b :kept-uuid logo-a :checksum "c1"}] dedupes))))

(deftest asset-without-a-uuid-aborts
  (is (thrown-with-msg?
       js/Error #"no uuid"
       (assets/dedupe-assets [(src "A" (dissoc (asset logo-a "logo.png" "c1") :block/uuid))]))))
