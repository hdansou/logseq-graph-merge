(ns graph-merge.stage.emit
  "S10 · Sort and emit the merged :graph-human export map (D1, D2)."
  (:require [clojure.walk :as walk]
            [graph-merge.stage.ontology :as ontology]
            [logseq.common.util :as common-util]
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

(defn- with-page-names
  "sqlite.build names a tag page after its EDN key (`build.cljs:473`), so a suffixed ident such as
   :user.class/warning-A04sq4Ln would create the page \"warning-a04sq4ln\" and its #[[uuid]]
   references would stop rendering. The definition is merged over that default, so setting
   :block/name here fixes the name. Properties already take their name from :block/title."
  [classes]
  (update-vals classes
               (fn [definition]
                 (cond-> definition
                   (and (:block/title definition) (not (:block/name definition)))
                   (assoc :block/name (common-util/page-name-sanity-lc (:block/title definition)))))))

(defn emit
  "Returns the export map to import. `graph-files` is only given for --config-from."
  [{:keys [pages-and-blocks properties classes graph-files]}]
  (strip-helper-keys
   (cond-> {:properties properties
            :classes (with-page-names classes)
            :pages-and-blocks (vec (sort-by page-sort-key pages-and-blocks))
            ::sqlite-export/export-type :graph-human}
     (seq graph-files) (assoc ::sqlite-export/graph-files (vec graph-files)))))
