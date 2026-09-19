(ns probe-phantom-context
  "Spike probe (read-only, on a copy): what surrounds the phantom :block/uuid index
   entries — incoming refs, and the nearest real entities by entity id.
   Usage: pnpm exec nbb-logseq -cp src spike/probe_phantom_context.cljs <graphs-dir> <graph-name>"
  (:require [datascript.core :as d]
            [logseq.db.common.sqlite-cli :as sqlite-cli]))

(let [[dir graph] *command-line-args*
      db @(sqlite-cli/open-db! dir graph)
      phantom-eids (into #{}
                         (comp (filter #(nil? (d/entity db (:e %)))) (map :e))
                         (d/datoms db :avet :block/uuid))
      refs (filterv #(and (int? (:v %)) (phantom-eids (:v %))) (d/datoms db :eavt))
      describe (fn [e]
                 (when-let [ent (d/entity db e)]
                   {:e e :ident (:db/ident ent) :title (:block/title ent)
                    :created (:block/created-at ent)}))
      nearest (fn [target step]
                (first (keep describe (take 200 (iterate #(+ % step) (+ target step))))))]
  (prn {:phantom-eids (vec (sort phantom-eids))
        :incoming-refs (mapv (fn [dat] {:from (:e dat) :attr (:a dat) :to (:v dat)}) refs)})
  (doseq [target (sort phantom-eids)]
    (prn {:phantom target :prev (nearest target -1) :next (nearest target 1)})))
