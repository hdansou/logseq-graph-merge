(ns graph-merge.blocks
  "Block helpers shared by the planner report and the post-import verification.")

(defn count-blocks
  "Counts blocks including all nested :build/children."
  [blocks]
  (count (mapcat #(tree-seq map? :build/children %) blocks)))
