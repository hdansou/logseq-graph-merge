#!/usr/bin/env bb
;; Spike probe: summarize a :graph-human export for T3.2 (built-in pages and page trees)
;; and T3.4 (whether asset blocks keep their uuids).
;; Usage: bb spike/analyze_export.clj <export.edn>
(require '[clojure.edn :as edn]
         '[clojure.pprint :refer [pprint]])

(def export (edn/read-string {:default tagged-literal} (slurp (first *command-line-args*))))

(defn blocks-seq [blocks]
  (mapcat #(cons % (blocks-seq (:build/children %))) blocks))

(def pages (:pages-and-blocks export))
(def all-blocks (mapcat (comp blocks-seq :blocks) pages))
(def asset? #(contains? (set (:build/tags %)) :logseq.class/Asset))
(def built-in-titles #{"Contents" "Library" "Quick add" "$$$views" "$$$favorites" "Recycle"})

(pprint
 {:top-level-keys (sort (keys export))
  :page-count (count pages)
  :page-map-keys (sort (distinct (mapcat (comp keys :page) pages)))
  :built-in-pages (for [{:keys [page blocks]} pages
                        :when (or (built-in-titles (:block/title page))
                                  (get-in page [:build/properties :logseq.property/built-in?]))]
                    {:title (:block/title page) :blocks (count (blocks-seq blocks))})
  :pages-with-parent (for [{:keys [page]} pages
                           :when (some #(re-find #"parent" (str %)) (keys page))]
                       (select-keys page [:block/title :block/parent :build/parent :logseq.property/parent]))
  :block-keys (sort (distinct (mapcat keys all-blocks)))
  :asset-blocks (for [b (concat all-blocks (map :page pages)) :when (asset? b)]
                  {:title (:block/title b)
                   :uuid (:block/uuid b)
                   :keep-uuid? (:build/keep-uuid? b)
                   :props (select-keys (:build/properties b)
                                       [:logseq.property.asset/type :logseq.property.asset/checksum])})
  :deleted-blocks (count (filter #(get-in % [:build/properties :logseq.property/deleted-at]) all-blocks))
  :blocks-with-uuid (count (filter :block/uuid all-blocks))
  :blocks-total (count all-blocks)
  :kv-idents (sort (map :db/ident (:logseq.db.sqlite.export/kv-values export)))
  :graph-files (map :file/path (:logseq.db.sqlite.export/graph-files export))
  :property-history-count (count (:logseq.db.sqlite.export/property-history export))})
