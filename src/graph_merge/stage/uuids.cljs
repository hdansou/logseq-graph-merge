(ns graph-merge.stage.uuids
  "S2 · Re-key uuids that appear as identities in more than one source (R9)."
  (:require [graph-merge.uuid :as guuid]))

(defn- identities
  "Uuids a source defines (maps carrying :block/uuid), in a stable walk order."
  [export]
  (->> (tree-seq coll? seq export)
       (filter map?)
       (keep :block/uuid)
       (remove guuid/shared-by-design?)
       distinct))

(defn rekey-collisions
  "Takes sources in precedence order. The first source to define a uuid keeps it; each later
   source that defines it again gets a derived uuid, with all of its own references rewritten.
   Returns {:sources [...] :rekeyed [{:graph :uuid :new-uuid}]}."
  [sources]
  (-> (reduce (fn [{:keys [seen] :as acc} {:keys [graph export] :as source}]
                (let [ids (identities export)
                      clashes (filter seen ids)
                      rekey (into {} (map (juxt identity #(guuid/derived-uuid graph %))) clashes)]
                  (-> acc
                      (update :sources conj (update source :export guuid/rewrite-uuids rekey))
                      (update :rekeyed into (map (fn [u] {:graph graph :uuid u :new-uuid (rekey u)})) clashes)
                      (update :seen into (map #(get rekey % %)) ids))))
              {:sources [] :rekeyed [] :seen #{}}
              sources)
      (dissoc :seen)))
