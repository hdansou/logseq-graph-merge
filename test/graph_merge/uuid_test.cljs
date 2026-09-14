(ns graph-merge.uuid-test
  (:require [cljs.test :refer [deftest is testing]]
            [graph-merge.uuid :as guuid]))

(def u1 #uuid "11111111-1111-4111-8111-111111111111")
(def u2 #uuid "22222222-2222-4222-8222-222222222222")

(deftest derived-uuid-is-deterministic-and-distinct
  (let [d (guuid/derived-uuid "GraphB" u1)]
    (is (uuid? d))
    (is (= d (guuid/derived-uuid "GraphB" u1)) "same inputs give the same uuid")
    (is (not= d u1))
    (is (not= d (guuid/derived-uuid "GraphC" u1)) "the graph name is part of the derivation")
    (testing "it is a version-5 uuid, so it never looks like Logseq's 0000000N- shared uuids"
      (is (= \5 (nth (str d) 14)))
      (is (not (guuid/shared-by-design? d))))))

(deftest shared-by-design-uuids-are-recognised
  (is (guuid/shared-by-design? #uuid "00000001-2026-0910-0000-000000000000") "journal")
  (is (guuid/shared-by-design? #uuid "00000004-1294-7765-6000-000000000000") "built-in page")
  (is (not (guuid/shared-by-design? u1))))

(deftest rewrite-uuids-renames-every-occurrence
  (let [data {:block/uuid u1
              :block/title (str "see [[" u1 "]] and #[[" u1 "]]")
              :build/properties {:user.property/rel [:block/uuid u1]}
              :graph-merge/parent-uuid u1
              :build/children [{:block/uuid u2 :block/title "untouched"}]
              :tags #{u1}}]
    (is (= {:block/uuid u2
            :block/title (str "see [[" u2 "]] and #[[" u2 "]]")
            :build/properties {:user.property/rel [:block/uuid u2]}
            :graph-merge/parent-uuid u2
            :build/children [{:block/uuid u2 :block/title "untouched"}]
            :tags #{u2}}
           (guuid/rewrite-uuids data {u1 u2})))
    (testing "an empty map returns the data unchanged"
      (is (identical? data (guuid/rewrite-uuids data {}))))))
