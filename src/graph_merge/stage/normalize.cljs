(ns graph-merge.stage.normalize
  "S1 · Normalize one source and recover the identities that :graph-human drops.
   Requirements: R2a (identity recovery), R7 (built-in pages), R11 (graph-level data)."
  (:require [logseq.common.uuid :as common-uuid]
            [logseq.db.sqlite.build :as sqlite-build]
            [logseq.db.sqlite.export :as sqlite-export]))

(def ^:private hidden-built-in-titles #{"$$$views" "Recycle"})

(defn- built-in? [page]
  (true? (get-in page [:build/properties :logseq.property/built-in?])))

(defn- skip-reason [{:keys [page]}]
  (cond
    (and (built-in? page) (hidden-built-in-titles (:block/title page))) :hidden-built-in
    (get-in page [:build/properties :logseq.property/deleted-at]) :deleted))

(defn- union-duplicate-built-ins
  "Merges repeated built-in pages into the first one, appending blocks in export order."
  [entries]
  (:entries
   (reduce (fn [{:keys [index] :as acc} {:keys [page] :as entry}]
             (let [title (when (built-in? page) (:block/title page))]
               (if-let [i (get index title)]
                 (update-in acc [:entries i :blocks] into (:blocks entry))
                 (cond-> (update acc :entries conj entry)
                   title (assoc-in [:index title] (count (:entries acc)))))))
           {:entries [] :index {}}
           entries)))

(defn- identity-error [message graph page rows]
  (ex-info (str message " page identity for \"" (:block/title page) "\" in graph " graph)
           {:graph graph :title (:block/title page) :created-at (:block/created-at page) :rows rows}))

(defn- matching-rows
  "A page the export kept a uuid for is matched by uuid; otherwise by (title, created-at)."
  [{:keys [by-uuid by-key]} page]
  (if-let [uuid (:block/uuid page)]
    (get by-uuid uuid)
    (get by-key [(:block/title page) (:block/created-at page)])))

(defn- recover-page
  [graph row-index page]
  (cond
    ;; Journals derive their uuid from the day. Tag/property page entries are exported as
    ;; {:block/uuid u} and must stay that way: sqlite.build treats one with :build/keep-uuid? as a
    ;; new page and fails on its missing title.
    (or (:build/journal page) (nil? (:block/title page)))
    page

    (built-in? page)
    (assoc page :block/uuid (common-uuid/gen-uuid :builtin-block-uuid (:block/title page)))

    :else
    (let [rows (matching-rows row-index page)]
      (case (count rows)
        1 (let [{:keys [uuid parent-uuid order]} (first rows)]
            (cond-> (assoc page :block/uuid uuid :build/keep-uuid? true)
              parent-uuid (assoc :graph-merge/parent-uuid parent-uuid)
              order (assoc :graph-merge/order order)))
        0 (throw (identity-error "unmatched" graph page rows))
        (throw (identity-error "ambiguous" graph page rows))))))

(defn- recover-asset [uuid-by-checksum block]
  (if-let [uuid (and (contains? (set (:build/tags block)) :logseq.class/Asset)
                     (uuid-by-checksum (get-in block [:build/properties :logseq.property.asset/checksum])))]
    (assoc block :block/uuid uuid :build/keep-uuid? true)
    block))

(defn normalize-source
  "Returns the source with a cleaned :export, its :graph-files set aside and the :skipped pages."
  [{:keys [graph export page-rows asset-rows] :as source}]
  (let [{skipped true kept false} (group-by (comp boolean skip-reason) (:pages-and-blocks export))
        row-index {:by-uuid (group-by :uuid page-rows)
                   :by-key (group-by (juxt :title :created-at) page-rows)}
        uuid-by-checksum (into {} (map (juxt :checksum :uuid)) asset-rows)
        entries (mapv (fn [entry]
                        (-> entry
                            (update :page #(recover-page graph row-index %))
                            (update :blocks sqlite-build/update-each-block #(recover-asset uuid-by-checksum %))))
                      (union-duplicate-built-ins kept))]
    (-> source
        (dissoc :page-rows :asset-rows)
        (assoc :export (-> export
                           (dissoc ::sqlite-export/kv-values
                                   ::sqlite-export/property-history
                                   ::sqlite-export/graph-files)
                           (assoc :pages-and-blocks entries))
               :graph-files (vec (::sqlite-export/graph-files export))
               :skipped (mapv (fn [{:keys [page] :as entry}]
                                (cond-> {:graph graph
                                         :title (:block/title page)
                                         :reason (skip-reason entry)}
                                  (:block/uuid page) (assoc :uuid (:block/uuid page))))
                              skipped)))))
