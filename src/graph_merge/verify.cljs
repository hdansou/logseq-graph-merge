(ns graph-merge.verify
  "Checks around the write: asset files before it, block counts after it (D5).
   `graph import` exits 0 even when it rejects the EDN (trap 6), so the result is checked by querying."
  (:require [graph-merge.blocks :as blocks]
            [logseq.common.uuid :as common-uuid]))

(defn- page-uuid [page]
  (or (:block/uuid page)
      (common-uuid/gen-uuid :journal-page-uuid (:build/journal page))))

(defn expected-block-counts
  "Page uuid -> number of blocks the export puts on that page."
  [export]
  (into {} (map (fn [{:keys [page blocks]}] [(page-uuid page) (blocks/count-blocks blocks)]))
        (:pages-and-blocks export)))

(defn shortfalls
  "Pages missing from the destination or holding fewer blocks than expected, sorted by uuid.
   Extra blocks are allowed: a fresh graph may seed its built-in pages."
  [expected actual]
  (->> (for [[uuid n] expected
             :let [found (get actual uuid 0)]
             :when (< found n)]
         {:uuid uuid :expected n :actual found})
       (sort-by (comp str :uuid))
       vec))

(defn timestamps-missing?
  "True when an export has pages that need (title, created-at) matching but carries no timestamps
   at all. Some CLI builds ignore :include-timestamps? in one of its shapes (trap 18)."
  [export]
  (let [pages (map :page (:pages-and-blocks export))
        needs-matching (remove #(or (:build/journal %) (:block/uuid %)) pages)]
    (boolean (and (seq needs-matching)
                  (not-any? :block/created-at pages)))))

(defn asset-problems
  "Asset files that are missing or whose checksum differs. `checksum-of` takes a file entry."
  [files checksum-of]
  (vec (for [file files
             :let [checksum (checksum-of file)]
             :when (not= checksum (:checksum file))]
         (assoc (select-keys file [:graph :uuid])
                :problem (if checksum :checksum-mismatch :missing-file)))))
