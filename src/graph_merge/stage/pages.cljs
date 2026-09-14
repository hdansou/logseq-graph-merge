(ns graph-merge.stage.pages
  "S7 · Match and merge pages across sources (R4, R5, R6, R6b, R7).
   See docs/merge-workflow.md §4."
  (:require [clojure.set :as set]
            [graph-merge.stage.ontology :as ontology]
            [graph-merge.uuid :as guuid]
            [logseq.common.uuid :as common-uuid]
            [logseq.db.common.order :as db-order]
            [logseq.db.frontend.class :as db-class]
            [logseq.db.frontend.property :as db-property]))

(defn- entry-kind [{:keys [page]}]
  (cond
    (:build/journal page) :journal
    (get-in page [:build/properties :logseq.property/built-in?]) :built-in
    ;; tag/property pages are exported as {:block/uuid u} with no title
    (nil? (:block/title page)) :definition-page
    :else :page))

(defn- groups-in-order
  "Like group-by, but returns the groups in order of first appearance."
  [key-fn entries]
  (let [groups (group-by key-fn entries)]
    (map groups (distinct (map key-fn entries)))))

(defn- merge-group
  "Merges a group of entries in precedence order into one: the first page map wins, later
   pages add their tags and any properties not already set, and blocks are appended in order."
  [entries]
  {:page (reduce (fn [page {other :page}]
                   (cond-> page
                     (seq (:build/tags other))
                     (update :build/tags (fnil into #{}) (:build/tags other))
                     (seq (:build/properties other))
                     (update :build/properties #(merge (:build/properties other) %))))
                 (:page (first entries))
                 (rest entries))
   :blocks (into [] (mapcat :blocks) entries)})

(defn- dedupe-favorites
  "Favorites are blocks linking to a page; keep one per link target. Dropped links are kept
   under ::dropped so the report can explain the block count."
  [entry]
  (let [[_ kept dropped] (reduce (fn [[seen kept dropped] block]
                                   (let [k (or (:block/link block) block)]
                                     (if (seen k)
                                       [seen kept (conj dropped k)]
                                       [(conj seen k) (conj kept block) dropped])))
                                 [#{} [] []]
                                 (:blocks entry))]
    (assoc entry :blocks kept ::dropped dropped)))

(defn- mapped-uuid [uuid-map entry]
  (let [u (get-in entry [:page :block/uuid])]
    (get uuid-map u u)))

(defn- parent-path
  "Normalized titles of a page's ancestors, root first, from the parents S1 recovered."
  [page-index page]
  (loop [parent-uuid (:graph-merge/parent-uuid page) path () seen #{}]
    (if-let [parent (and parent-uuid (not (seen parent-uuid)) (get page-index parent-uuid))]
      (recur (:graph-merge/parent-uuid parent)
             (conj path (ontology/normalize-title (:block/title parent)))
             (conj seen parent-uuid))
      (vec path))))

(defn- title-key
  "Pages group by normalized title. A blank title identifies nothing, so such a page only
   groups with itself (the 5-graph dry run had merged 7 unrelated untitled pages)."
  [page]
  (let [title (ontology/normalize-title (:block/title page))]
    (if (= "" title) (:block/uuid page) title)))

(defn- tags-of [entry]
  (disj (set (get-in entry [:page :build/tags])) :logseq.class/Page))

(defn- tag-clusters
  "Clusters entries whose tag sets overlap, transitively."
  [entries]
  (reduce (fn [clusters entry]
            (let [tags (tags-of entry)
                  overlaps? (fn [cluster] (some #(seq (set/intersection tags (tags-of %))) cluster))
                  {joined true apart false} (group-by (comp boolean overlaps?) clusters)]
              (conj (vec apart) (into [entry] cat joined))))
          []
          entries))

(defn- page-clusters
  "Splits one title+path group into pages (R6): overlapping tags merge; untagged pages join
   the only tagged cluster, otherwise they form their own. Members and clusters are in precedence order."
  [group]
  (let [{tagged true untagged false} (group-by (comp boolean seq tags-of) group)
        clusters (tag-clusters tagged)
        clusters (cond
                   (empty? untagged) clusters
                   (= 1 (count clusters)) [(into (first clusters) untagged)]
                   :else (conj clusters (vec untagged)))]
    (->> clusters
         (map #(vec (sort-by :position %)))
         (sort-by (comp :position first)))))

(defn- merged-away-uuids
  "Maps every later member's page uuid to the uuid of the cluster's first member."
  [cluster]
  (let [kept (get-in (first cluster) [:page :block/uuid])]
    (into {} (for [{:keys [page]} (rest cluster)
                   :let [u (:block/uuid page)]
                   :when (and u (not= u kept))]
               [u kept]))))

(def ^:private built-in-uuid-by-title
  "Exact titles of built-in tags and properties. Import matches page titles exactly
   (ldb/get-case-page), so a user page 'Card' would land on the built-in #Card entity.
   Classes come last so they win a title shared with a property."
  (into {} (for [[ident {:keys [title]}] (concat db-property/built-in-properties db-class/built-in-classes)
                 :when title]
             [title (common-uuid/gen-uuid :db-ident-block-uuid ident)])))

(defn- class-page-conversions
  "Top-level regular pages titled like a built-in tag/property (exactly) or a merged class
   (normalized) become entries of that definition's page (R4). Returns {:entries :uuid-map :report}."
  [entries page-index classes]
  (let [class-uuid-by-title (into {} (map (fn [c] [(ontology/normalize-title (:block/title c)) (:block/uuid c)]))
                                  (vals classes))
        definition-uuid (fn [title]
                          (or (built-in-uuid-by-title title)
                              (class-uuid-by-title (ontology/normalize-title title))))]
    (reduce (fn [acc {:keys [page graph] :as entry}]
              (if-let [class-uuid (and (= :page (entry-kind entry))
                                       (empty? (parent-path page-index page))
                                       (definition-uuid (:block/title page)))]
                (-> acc
                    (update :entries conj (assoc entry :page {:block/uuid class-uuid}))
                    (assoc-in [:uuid-map (:block/uuid page)] class-uuid)
                    (update :report conj {:graph graph :title (:block/title page) :class-uuid class-uuid}))
                (update acc :entries conj entry)))
            {:entries [] :uuid-map {} :report []}
            entries)))

(defn- sibling-sort-key [{:keys [page source-index]}]
  [source-index (str (:graph-merge/order page)) (:block/title page)])

(defn- attach-tree
  "Restores page parents through the page uuid map and gives siblings fresh, ordered keys (R6b).
   A parent that isn't emitted (and isn't a shared built-in) re-roots the page."
  [entries uuid-map]
  (let [emitted (set (keep (comp :block/uuid :page) entries))
        parent-of (fn [{:keys [page]}]
                    (some-> (:graph-merge/parent-uuid page) (#(get uuid-map % %))))
        valid? #(or (emitted %) (guuid/shared-by-design? %))
        order-by-uuid (->> entries
                           (filter #(some-> (parent-of %) valid?))
                           (group-by parent-of)
                           (mapcat (fn [[_ siblings]]
                                     (map vector
                                          (map (comp :block/uuid :page) (sort-by sibling-sort-key siblings))
                                          (db-order/gen-n-keys (count siblings) nil nil :max-key-atom (atom nil)))))
                           (into {}))]
    {:entries (mapv (fn [{:keys [page] :as entry}]
                      (let [parent (parent-of entry)]
                        (-> entry
                            (dissoc :source-index)
                            (assoc :page (cond-> (dissoc page :graph-merge/parent-uuid :graph-merge/order)
                                           (and parent (valid? parent))
                                           (assoc :block/parent [:block/uuid parent]
                                                  :block/order (order-by-uuid (:block/uuid page))))))))
                    entries)
     :re-rooted (vec (for [{:keys [page] :as entry} entries
                           :let [parent (parent-of entry)]
                           :when (and parent (not (valid? parent)))]
                       {:title (:block/title page) :missing-parent parent}))}))

(defn- merge-cluster [cluster]
  (assoc (merge-group cluster) :source-index (:source-index (first cluster))))

(defn merge-pages
  "Takes sources in precedence order, the merged classes and the uuid map from earlier stages.
   Returns {:pages-and-blocks :uuid-map :page-merges :definition-page-merges :favorite-dedupes :re-rooted}."
  [{:keys [sources classes uuid-map]}]
  (let [entries (map-indexed (fn [position entry] (assoc entry :position position))
                             (for [[source-index {:keys [graph export]}] (map-indexed vector sources)
                                   entry (:pages-and-blocks export)]
                               (assoc entry :graph graph :source-index source-index)))
        page-index (into {} (keep (fn [{:keys [page]}] (when (:block/uuid page) [(:block/uuid page) page]))) entries)
        conversions (class-page-conversions entries page-index classes)
        by-kind (group-by entry-kind (:entries conversions))
        journals (map merge-cluster (groups-in-order (comp :build/journal :page) (:journal by-kind)))
        built-ins (for [group (groups-in-order (comp :block/title :page) (:built-in by-kind))
                        :let [entry (merge-cluster group)]]
                    (cond-> entry
                      (= "$$$favorites" (get-in entry [:page :block/title])) dedupe-favorites))
        definition-pages (for [group (groups-in-order (partial mapped-uuid uuid-map) (:definition-page by-kind))]
                           (assoc-in (merge-cluster group) [:page :block/uuid]
                                     (mapped-uuid uuid-map (first group))))
        clusters (->> (:page by-kind)
                      (groups-in-order (juxt (comp title-key :page)
                                             #(parent-path page-index (:page %))))
                      (mapcat page-clusters))
        page-uuid-map (into (:uuid-map conversions) (map merged-away-uuids) clusters)
        {tree-entries :entries re-rooted :re-rooted}
        (attach-tree (vec (concat journals (map #(dissoc % ::dropped) built-ins)
                                  definition-pages (map merge-cluster clusters)))
                     (merge uuid-map page-uuid-map))]
    {:pages-and-blocks tree-entries
     :uuid-map page-uuid-map
     :page-merges (vec (for [cluster clusters
                             :when (next cluster)
                             :let [{:keys [page]} (first cluster)]]
                         {:title (:block/title page) :uuid (:block/uuid page)
                          :members (mapv (fn [{:keys [graph page]}] {:graph graph :title (:block/title page)}) cluster)}))
     :definition-page-merges (:report conversions)
     :favorite-dedupes (vec (mapcat ::dropped built-ins))
     :re-rooted re-rooted}))
