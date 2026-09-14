(ns graph-merge.stage.ontology
  "S3 · Unify properties (R3) and S4 · unify tags/classes (R4) across sources.
   Both group definitions by normalized title; the first member in precedence order is canonical.
   See docs/merge-workflow.md §3."
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [logseq.common.uuid :as common-uuid]
            [logseq.db.frontend.db-ident :as db-ident]))

(defn normalize-title [title]
  (-> (str title) string/trim string/lower-case))

(defn- title-groups
  "Definitions of `kind` (:properties or :classes) grouped by normalized title. Groups are sorted
   by title, and members are in precedence order (source order, then ident), so output is stable."
  [sources kind]
  (->> (for [{:keys [graph export]} sources
             [ident definition] (sort-by key (get export kind))]
         {:graph graph :ident ident :def definition})
       (group-by (comp normalize-title :block/title :def))
       (sort-by key)
       (map val)))

(def ^:private identity-keys
  #{:block/title :block/uuid :build/keep-uuid? :block/created-at :block/updated-at :block/collapsed?})

(defn- schema-diff
  "Attributes whose values differ, ignoring identity/timestamp keys and the `merged` keys
   that a stage combines instead of choosing one."
  [canonical other merged]
  (->> (into (set (keys canonical)) (keys other))
       (remove #(or (identity-keys %) (merged %)))
       (filter #(not= (get canonical %) (get other %)))
       sort
       vec))

(defn- property-schema [definition]
  {:logseq.property/type (get definition :logseq.property/type :default)
   :db/cardinality (get definition :db/cardinality :db.cardinality/one)})

(defn- union-closed-values
  "Appends `others` to `canonical`, merging choices whose values match (never blank ones).
   Returns [closed-values uuid-map] where uuid-map maps merged-away choice uuids to kept ones."
  [canonical others]
  (reduce (fn [[kept uuid-map] {:keys [value uuid] :as choice}]
            (let [k (normalize-title value)
                  same (when-not (string/blank? k)
                         (some #(when (= k (normalize-title (:value %))) %) kept))]
              (if same
                [kept (assoc uuid-map uuid (:uuid same))]
                [(conj kept choice) uuid-map])))
          [(vec canonical) {}]
          others))

(defn- with-kept-uuid
  "Gives the merged definition at [kind ident] a kept uuid: the first member's that has one
   (members start with the canonical one), else the ident-derived uuid sqlite.build uses by default.
   Other members' uuids are mapped to it, so their tag/property page entries find it (S7)."
  [acc kind ident members]
  (let [uuid (or (some (comp :block/uuid :def) members)
                 (common-uuid/gen-uuid :db-ident-block-uuid ident))]
    (-> acc
        (update-in [kind ident] assoc :block/uuid uuid :build/keep-uuid? true)
        (update :uuid-map into (for [{:keys [def]} members
                                     :let [member-uuid (:block/uuid def)]
                                     :when (and member-uuid (not= member-uuid uuid))]
                                 [member-uuid uuid])))))

(defn- renamed [{:keys [graph ident def]}]
  (let [title (string/trim (:block/title def))]
    {:new-title (str title " (" graph ")")
     :new-ident (keyword (namespace ident) (db-ident/normalize-ident-name-part (str title "-" graph)))}))

(defn- merge-compatible-property
  [acc canonical-ident canonical-def {:keys [graph ident def]}]
  (let [[closed-values uuid-map] (union-closed-values
                                  (get-in acc [:properties canonical-ident :build/closed-values])
                                  (:build/closed-values def))
        diff (schema-diff canonical-def def #{:build/closed-values :logseq.property/type :db/cardinality})]
    (cond-> (update acc :uuid-map merge uuid-map)
      (not= ident canonical-ident) (assoc-in [:ident-maps graph ident] canonical-ident)
      (seq closed-values) (assoc-in [:properties canonical-ident :build/closed-values] closed-values)
      (seq diff) (update :diffs conj {:kind :property :graph graph :title (:block/title def) :attrs diff}))))

(defn- add-renamed-property
  [acc canonical-def {:keys [graph ident def] :as member}]
  (let [{:keys [new-title new-ident]} (renamed member)]
    (-> acc
        (assoc-in [:properties new-ident] (assoc def :block/title new-title :graph-merge/graph graph))
        (with-kept-uuid :properties new-ident [member])
        (assoc-in [:ident-maps graph ident] new-ident)
        (update :renames conj {:graph graph :title (:block/title def) :ident ident
                               :new-ident new-ident :new-title new-title
                               :canonical (property-schema canonical-def)
                               :actual (property-schema def)}))))

(defn unify-properties
  "Returns {:properties merged-definitions :ident-maps {graph {ident canonical-ident}}
            :uuid-map {closed-value-uuid kept-uuid} :renames [...] :diffs [...]}."
  [sources]
  (reduce (fn [acc [{canonical-ident :ident canonical-def :def graph :graph :as canonical} & others]]
            (let [compatible? #(= (property-schema canonical-def) (property-schema (:def %)))
                  compatible (filter compatible? others)
                  acc (reduce #(merge-compatible-property %1 canonical-ident canonical-def %2)
                              (assoc-in acc [:properties canonical-ident]
                                        (assoc canonical-def :graph-merge/graph graph))
                              compatible)
                  acc (with-kept-uuid acc :properties canonical-ident (cons canonical compatible))]
              (reduce #(add-renamed-property %1 canonical-def %2) acc (remove compatible? others))))
          {:properties {} :ident-maps {} :uuid-map {} :renames [] :diffs []}
          (title-groups sources :properties)))

(defn- class-ident-maps
  "Maps every non-canonical class ident to its group's canonical ident, per graph."
  [groups]
  (reduce (fn [acc [{canonical :ident} & others]]
            (reduce (fn [acc {:keys [graph ident]}]
                      (cond-> acc (not= ident canonical) (assoc-in [graph ident] canonical)))
                    acc
                    others))
          {}
          groups))

(defn- map-ident [ident-maps graph ident]
  (get-in ident-maps [graph ident] ident))

(defn- with-mapped-refs
  "A class definition with its property and parent idents mapped to canonical ones."
  [property-ident-maps class-ident-maps {:keys [graph def]}]
  (cond-> def
    (:build/class-properties def)
    (update :build/class-properties #(mapv (partial map-ident property-ident-maps graph) %))
    (:build/class-extends def)
    (update :build/class-extends #(set (map (partial map-ident class-ident-maps graph) %)))))

(defn- extends-cycle? [classes ident]
  (loop [todo (vec (get-in classes [ident :build/class-extends])) seen #{}]
    (if-let [[parent & more] (seq todo)]
      (cond
        (= parent ident) true
        (seen parent) (recur (vec more) seen)
        :else (recur (into (vec more) (get-in classes [parent :build/class-extends])) (conj seen parent)))
      false)))

(defn- set-extends [definition parents]
  (if (seq parents)
    (assoc definition :build/class-extends parents)
    (dissoc definition :build/class-extends)))

(defn unify-classes
  "Takes sources in precedence order and the S3 property ident maps.
   Returns {:classes merged-definitions :ident-maps {graph {ident canonical-ident}}
            :diffs [...] :cycles [...]}.
   A cycle is detected when its last member is merged, since every earlier class is in place by then."
  [sources property-ident-maps]
  (let [groups (title-groups sources :classes)
        ident-maps (class-ident-maps groups)
        mapped (partial with-mapped-refs property-ident-maps ident-maps)]
    (reduce
     (fn [acc [{ident :ident :as canonical} & others :as group]]
       (let [canonical-def (mapped canonical)
             properties (vec (distinct (mapcat (comp :build/class-properties mapped) group)))
             parents (set (mapcat (comp :build/class-extends mapped) group))
             merged (cond-> (-> canonical-def
                                (assoc :graph-merge/graph (:graph canonical))
                                (set-extends parents))
                      (seq properties) (assoc :build/class-properties properties))
             acc (-> acc
                     (assoc-in [:classes ident] merged)
                     (update :diffs into
                             (for [{:keys [graph def]} others
                                   :let [diff (schema-diff (:def canonical) def
                                                           #{:build/class-properties :build/class-extends})]
                                   :when (seq diff)]
                               {:kind :class :graph graph :title (:block/title def) :attrs diff})))]
         (cond-> acc
           (extends-cycle? (:classes acc) ident)
           (as-> acc (let [kept (set (:build/class-extends canonical-def))]
                       (-> acc
                           (assoc-in [:classes ident] (set-extends merged kept))
                           (update :cycles conj {:kind :extends-cycle :title (:block/title canonical-def)
                                                 :ident ident :rejected (set/difference parents kept)}))))
           true
           (with-kept-uuid :classes ident group))))
     {:classes {} :ident-maps ident-maps :uuid-map {} :diffs [] :cycles []}
     groups)))
