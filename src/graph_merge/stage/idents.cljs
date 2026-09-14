(ns graph-merge.stage.idents
  "S5 · Rewrite property and class idents to the canonical ones chosen by S3/S4 (R8)."
  (:require [clojure.string :as string]
            [clojure.walk :as walk]))

(defn- strings-in [data]
  (filter string? (tree-seq coll? seq data)))

(defn rewrite-idents
  "Takes the sources plus S3/S4 results. Each source's pages and blocks, and each merged
   definition (by its :graph-merge/graph origin), get that graph's ident maps applied.
   Idents inside strings (e.g. query text) are left alone and reported."
  [{:keys [sources properties classes property-ident-maps class-ident-maps]}]
  (let [ident-map (fn [graph] (merge (get property-ident-maps graph) (get class-ident-maps graph)))
        rewrite-definition #(walk/postwalk-replace (ident-map (:graph-merge/graph %)) %)]
    {:sources (mapv (fn [{:keys [graph] :as source}]
                      (update source :export #(-> %
                                                  (dissoc :properties :classes)
                                                  (->> (walk/postwalk-replace (ident-map graph))))))
                    sources)
     :properties (update-vals properties rewrite-definition)
     :classes (update-vals classes rewrite-definition)
     :strings-with-idents (vec (for [{:keys [graph export]} sources
                                     :let [texts (strings-in (:pages-and-blocks export))]
                                     ident (sort (keys (ident-map graph)))
                                     :when (some #(string/includes? % (str ident)) texts)]
                                 {:graph graph :ident ident}))}))
