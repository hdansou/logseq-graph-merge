(ns graph-merge.stage.refs
  "S8 · Point every reference at the uuid it was merged into (R8)."
  (:require [graph-merge.uuid :as guuid]))

(defn resolve-uuid-maps
  "Combines stage uuid maps (old -> new) and follows chains to their final uuid.
   If a chain cycles, the direct mapping is kept."
  [uuid-maps]
  (let [combined (apply merge uuid-maps)
        final (fn [u]
                (loop [current (get combined u) seen #{u}]
                  (cond
                    (seen current) (get combined u)
                    (contains? combined current) (recur (get combined current) (conj seen current))
                    :else current)))]
    (into {} (map (juxt key (comp final key))) combined)))

(defn rewrite-refs
  "Rewrites uuid references in merged pages and blocks and in merged definitions."
  [merged uuid-maps]
  (let [uuid-map (resolve-uuid-maps uuid-maps)]
    (-> merged
        (update :pages-and-blocks guuid/rewrite-uuids uuid-map)
        (update :properties guuid/rewrite-uuids uuid-map)
        (update :classes guuid/rewrite-uuids uuid-map))))
