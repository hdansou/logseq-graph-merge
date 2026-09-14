(ns graph-merge.stage.uuids-test
  (:require [cljs.test :refer [deftest is testing]]
            [graph-merge.stage.uuids :as uuids]
            [graph-merge.uuid :as guuid]))

(def shared #uuid "5aaaaaaa-0000-4000-8000-000000000001")
(def page-a #uuid "a0000000-0000-4000-8000-000000000001")
(def page-b #uuid "b0000000-0000-4000-8000-000000000001")

(defn- src [graph page-uuid & blocks]
  {:graph graph
   :export {:pages-and-blocks [{:page {:block/title "P" :block/uuid page-uuid :build/keep-uuid? true}
                                :blocks (vec blocks)}]}})

(defn- blocks-of [source]
  (-> source :export :pages-and-blocks first :blocks))

(deftest sources-without-collisions-are-unchanged
  (let [sources [(src "A" page-a {:block/uuid shared :block/title "a"})
                 (src "B" page-b {:block/title "b"})]
        {:keys [sources rekeyed]} (uuids/rekey-collisions sources)]
    (is (= [] rekeyed))
    (is (= "a" (-> sources first blocks-of first :block/title)))))

(deftest later-source-collision-is-rekeyed-with-its-references
  (testing "graphs cloned from one template share block uuids; a silent upsert would overwrite (trap 3)"
    (let [sources [(src "A" page-a
                        {:block/uuid shared :block/title "A target"}
                        {:block/title (str "A ref [[" shared "]]")})
                   (src "B" page-b
                        {:block/uuid shared :block/title "B target"}
                        {:block/title "B ref" :build/properties {:user.property/rel [:block/uuid shared]}})]
          new-uuid (guuid/derived-uuid "B" shared)
          {[a b] :sources rekeyed :rekeyed} (uuids/rekey-collisions sources)]
      (testing "the first source keeps its uuid and its refs"
        (is (= shared (-> a blocks-of first :block/uuid)))
        (is (= (str "A ref [[" shared "]]") (-> a blocks-of second :block/title))))
      (testing "the later source gets a derived uuid, and its own references follow"
        (is (= new-uuid (-> b blocks-of first :block/uuid)))
        (is (= [:block/uuid new-uuid] (-> b blocks-of second :build/properties :user.property/rel))))
      (is (= [{:graph "B" :uuid shared :new-uuid new-uuid}] rekeyed)))))

(deftest shared-by-design-uuids-are-not-rekeyed
  (testing "built-in and journal uuids are equal across graphs on purpose"
    (let [library #uuid "00000004-1294-7765-6000-000000000000"
          sources [(src "A" library) (src "B" library)]]
      (is (= [] (:rekeyed (uuids/rekey-collisions sources)))))))
