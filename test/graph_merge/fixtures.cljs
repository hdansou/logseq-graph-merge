(ns graph-merge.fixtures
  "Shared builders for planner tests. They mirror the real :graph-human export shape
   seen in the T3 spike, and the identity query rows the extract phase produces.")

(def t0 1700000000000)

(defn source
  "A planner source: the graph's :graph-human export plus its identity query rows."
  [graph export & {:keys [page-rows asset-rows]}]
  {:graph graph
   :export export
   :page-rows (vec page-rows)
   :asset-rows (vec asset-rows)})

(defn page
  "A page entry as exported by :graph-human: a title and created-at, usually no uuid."
  [title created-at & {:keys [blocks properties]}]
  (cond-> {:page {:block/title title :block/created-at created-at}
           :blocks (vec blocks)}
    properties (assoc-in [:page :build/properties] properties)))

(defn built-in-page [title & {:keys [blocks]}]
  (page title t0 :blocks blocks :properties {:logseq.property/built-in? true}))

(defn page-row
  "An identity query row for a page."
  [title created-at uuid & {:keys [parent-uuid order]}]
  {:uuid uuid :title title :created-at created-at :parent-uuid parent-uuid :order order})

(defn titles [export]
  (mapv #(get-in % [:page :block/title]) (:pages-and-blocks export)))
