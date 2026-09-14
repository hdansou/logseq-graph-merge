(ns graph-merge.stage.emit
  "S10 · Sort and emit the merged :graph-human export map (D1, D2)."
  (:require [clojure.walk :as walk]
            [graph-merge.stage.ontology :as ontology]
            [logseq.db.sqlite.export :as sqlite-export]))

(defn- page-sort-key [{:keys [page]}]
  (if-let [day (:build/journal page)]
    [0 day ""]
    [1 (ontology/normalize-title (:block/title page)) (str (:block/uuid page))]))

(defn- strip-helper-keys [data]
  (walk/postwalk (fn [x]
                   (if (map? x)
                     (into (empty x) (remove (fn [[k _]] (and (keyword? k) (= "graph-merge" (namespace k))))) x)
                     x))
                 data))

(defn emit
  "Returns the export map to import. `graph-files` is only given for --config-from."
  [{:keys [pages-and-blocks properties classes graph-files]}]
  (strip-helper-keys
   (cond-> {:properties properties
            :classes classes
            :pages-and-blocks (vec (sort-by page-sort-key pages-and-blocks))
            ::sqlite-export/export-type :graph-human}
     (seq graph-files) (assoc ::sqlite-export/graph-files (vec graph-files)))))
