(ns graph-merge.stage.emit-test
  (:require [cljs.test :refer [deftest is testing]]
            [graph-merge.stage.emit :as emit]
            [logseq.db.sqlite.export :as sqlite-export]))

(deftest pages-are-sorted-journals-first-then-by-title
  (let [{:keys [pages-and-blocks]}
        (emit/emit {:pages-and-blocks [{:page {:block/title "banana"}}
                                       {:page {:build/journal 20260911}}
                                       {:page {:block/title "Apple"}}
                                       {:page {:build/journal 20260910}}]
                    :properties {} :classes {}})]
    (is (= [20260910 20260911 "Apple" "banana"]
           (map #(or (get-in % [:page :build/journal]) (get-in % [:page :block/title])) pages-and-blocks)))))

(deftest helper-keys-are-stripped-everywhere
  (let [export (emit/emit {:pages-and-blocks [{:page {:block/title "P" :graph-merge/order "a0"}
                                               :blocks [{:block/title "b" :graph-merge/x 1}]}]
                           :properties {:user.property/p {:block/title "p" :graph-merge/graph "A"}}
                           :classes {:user.class/c {:block/title "c" :graph-merge/graph "A"}}})]
    (is (empty? (filter #(and (keyword? %) (= "graph-merge" (namespace %)))
                        (tree-seq coll? seq export))))))

(deftest export-type-and-optional-config-files
  (let [files [{:file/path "logseq/config.edn" :file/content "{}"}]
        base {:pages-and-blocks [] :properties {} :classes {}}]
    (is (= :graph-human (::sqlite-export/export-type (emit/emit base))))
    (testing "graph files are only included for --config-from"
      (is (not (contains? (emit/emit base) ::sqlite-export/graph-files)))
      (is (= files (::sqlite-export/graph-files (emit/emit (assoc base :graph-files files))))))))

(deftest classes-carry-a-page-name-derived-from-their-title
  (testing "sqlite.build names a tag page after the EDN key (build.cljs:473), so a suffixed ident
            would name it 'warning-a04sq4ln' and its #[[uuid]] references stop rendering"
    (let [export (emit/emit {:pages-and-blocks [] :properties {}
                             :classes {:user.class/warning-A04sq4Ln {:block/title "Warning"}}})]
      (is (= "warning" (get-in export [:classes :user.class/warning-A04sq4Ln :block/name])))))
  (testing "a name already in the definition is kept"
    (let [export (emit/emit {:pages-and-blocks [] :properties {}
                             :classes {:user.class/c {:block/title "C" :block/name "kept"}}})]
      (is (= "kept" (get-in export [:classes :user.class/c :block/name]))))))
