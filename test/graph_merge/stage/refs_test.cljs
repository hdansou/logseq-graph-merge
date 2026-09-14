(ns graph-merge.stage.refs-test
  (:require [cljs.test :refer [deftest is testing]]
            [graph-merge.stage.refs :as refs]))

(def a #uuid "a0000000-0000-4000-8000-00000000000a")
(def b #uuid "b0000000-0000-4000-8000-00000000000b")
(def c #uuid "c0000000-0000-4000-8000-00000000000c")

(deftest stage-maps-combine-and-chains-resolve
  (testing "a page merged into another page that itself became a class page"
    (is (= {a c b c} (refs/resolve-uuid-maps [{a b} {b c}]))))
  (testing "a cycle can't loop forever; it stops at the first repeat"
    (is (= {a b b a} (refs/resolve-uuid-maps [{a b} {b a}])))))

(deftest refs-follow-merged-uuids-everywhere
  (let [{:keys [pages-and-blocks properties]}
        (refs/rewrite-refs {:pages-and-blocks [{:page {:block/title "P" :block/uuid c}
                                                :blocks [{:block/title (str "see [[" a "]]")
                                                          :build/properties {:user.property/rel [:block/uuid a]}}]}]
                            :properties {:user.property/s {:build/properties {:logseq.property/default-value [:block/uuid b]}}}
                            :classes {}}
                           [{a b} {b c}])]
    (is (= (str "see [[" c "]]") (-> pages-and-blocks first :blocks first :block/title)))
    (is (= [:block/uuid c] (-> pages-and-blocks first :blocks first :build/properties :user.property/rel)))
    (is (= [:block/uuid c] (get-in properties [:user.property/s :build/properties :logseq.property/default-value])))))
