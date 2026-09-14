(ns graph-merge.stage.values
  "S1b · Values Logseq's importer rejects (R2b). Runs per source right after S1, while the source's
   own property definitions and skipped pages are known. Information is kept as note blocks."
  (:require [clojure.string :as string]
            [logseq.db.frontend.property :as db-property]))

(defn- property-title [properties ident]
  (or (get-in properties [ident :block/title])
      (get-in db-property/built-in-properties [ident :title])
      (name ident)))

(defn- ref-uuid [value]
  (when (and (vector? value) (= :block/uuid (first value)))
    (second value)))

(defn- clean-property
  "Returns {:keep value-or-nil :notes [...] :fixes [...]} for one property value."
  [{:keys [properties deleted]} node ident value]
  (let [fix (fn [kind & {:as more}] (merge {:node node :property ident :fix kind} more))
        deleted-note #(str (property-title properties ident) ": " (deleted %) " (deleted page)")]
    (cond
      (and (= :logseq.property/empty-placeholder value)
           (= :number (get-in properties [ident :logseq.property/type])))
      {:fixes [(fix :dropped-empty-value)]}

      (and (map? value) (:build/property-value value)
           (seq (get-in db-property/built-in-properties [ident :closed-values])))
      {:notes [(str (property-title properties ident) ": " (:block/title value))]
       :fixes [(fix :invalid-choice-note :value (:block/title value))]}

      (deleted (ref-uuid value))
      {:notes [(deleted-note (ref-uuid value))]
       :fixes [(fix :deleted-page-ref :value (deleted (ref-uuid value)))]}

      (and (coll? value) (not (map? value)) (some (comp deleted ref-uuid) value))
      (let [{gone true kept false} (group-by (comp boolean deleted ref-uuid) value)]
        {:keep (when (seq kept) (into (empty value) kept))
         :notes (mapv (comp deleted-note ref-uuid) (sort-by str gone))
         :fixes (mapv #(fix :deleted-page-ref :value (deleted (ref-uuid %))) (sort-by str gone))})

      :else
      {:keep value})))

(defn- clean-node
  "Cleans a page or block map. Returns [node note-blocks fixes]."
  [ctx node]
  (let [title (:block/title node)
        results (for [[ident value] (sort-by key (:build/properties node))]
                  [ident (clean-property ctx title ident value)])
        properties (into {} (keep (fn [[ident {:keys [keep]}]] (when (some? keep) [ident keep]))) results)
        text-link #"#?\[\[([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\]\]"
        node (cond-> node
               (:build/properties node) (assoc :build/properties properties)
               (and (:build/properties node) (empty? properties)) (dissoc :build/properties)
               (string? title) (assoc :block/title
                                      (string/replace title text-link
                                                      (fn [[link uuid-text]]
                                                        (or ((:deleted-by-text ctx) uuid-text) link)))))]
    [node
     (vec (for [[_ {:keys [notes]}] results note notes] {:block/title note}))
     (vec (mapcat (comp :fixes second) results))]))

(defn- clean-blocks [ctx fixes blocks]
  (mapv (fn [block]
          (let [[block notes block-fixes] (clean-node ctx block)
                children (into (clean-blocks ctx fixes (:build/children block)) notes)]
            (vswap! fixes into block-fixes)
            (cond-> (dissoc block :build/children)
              (seq children) (assoc :build/children children))))
        blocks))

(defn clean-values
  "Takes a normalized source (S1 output). Returns it with a cleaned :export and :value-fixes."
  [{:keys [graph export skipped] :as source}]
  (let [deleted (into {} (keep (fn [{:keys [uuid title]}] (when uuid [uuid title]))) skipped)
        ctx {:properties (:properties export)
             :deleted deleted
             :deleted-by-text (update-keys deleted str)}
        fixes (volatile! [])
        entries (mapv (fn [{:keys [page blocks] :as entry}]
                        (let [[page notes page-fixes] (clean-node ctx page)
                              entry (assoc entry
                                           :page page
                                           :blocks (into (clean-blocks ctx fixes blocks) notes))]
                          (vswap! fixes into page-fixes)
                          entry))
                      (:pages-and-blocks export))]
    (assoc source
           :export (assoc export :pages-and-blocks entries)
           :value-fixes (mapv #(assoc % :graph graph) @fixes))))
