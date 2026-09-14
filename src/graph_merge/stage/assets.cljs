(ns graph-merge.stage.assets
  "S6 · Deduplicate assets by checksum and list the files to copy (R10)."
  (:require [logseq.db.sqlite.build :as sqlite-build]))

(defn- asset? [block]
  (contains? (set (:build/tags block)) :logseq.class/Asset))

(defn dedupe-assets
  "Walks sources in precedence order (pages in order, blocks depth-first). The first asset
   with a checksum is kept and listed in :files; a later one is replaced by a [[kept-uuid]]
   block that keeps its children, and its uuid is mapped in :uuid-map for S8.
   Returns {:sources :files :uuid-map :dedupes}."
  [sources]
  (let [kept (volatile! {})
        files (volatile! [])
        uuid-map (volatile! {})
        dedupes (volatile! [])
        visit (fn [graph block]
                (if-not (asset? block)
                  block
                  (let [{:logseq.property.asset/keys [checksum type]} (:build/properties block)
                        uuid (:block/uuid block)]
                    (when-not uuid
                      (throw (ex-info (str "Asset \"" (:block/title block) "\" in graph " graph
                                           " has no uuid, so its file can't be located")
                                      {:graph graph :checksum checksum})))
                    (if-let [kept-uuid (get @kept checksum)]
                      (do (vswap! uuid-map assoc uuid kept-uuid)
                          (vswap! dedupes conj {:graph graph :title (:block/title block) :uuid uuid
                                                :kept-uuid kept-uuid :checksum checksum})
                          (cond-> {:block/title (str "[[" kept-uuid "]]")}
                            (:build/children block) (assoc :build/children (:build/children block))))
                      (do (vswap! kept assoc checksum uuid)
                          (vswap! files conj {:graph graph :uuid uuid :type type :checksum checksum})
                          block)))))
        sources (mapv (fn [{:keys [graph] :as source}]
                        (update-in source [:export :pages-and-blocks]
                                   (partial mapv #(update % :blocks sqlite-build/update-each-block
                                                          (partial visit graph)))))
                      sources)]
    {:sources sources :files @files :uuid-map @uuid-map :dedupes @dedupes}))
