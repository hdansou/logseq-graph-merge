(ns probe-eid-history
  "Spike probe (read-only, on copies): for given entity ids, print whether they exist
   in this snapshot and what they look like.
   Usage: pnpm exec nbb-logseq -cp src spike/probe_eid_history.cljs <graphs-dir> <graph-name> <eid>..."
  (:require [datascript.core :as d]
            [logseq.db.common.sqlite-cli :as sqlite-cli]))

(let [[dir graph & eids] *command-line-args*
      db @(sqlite-cli/open-db! dir graph)]
  (doseq [eid (map js/parseInt eids)]
    (let [ent (d/entity db eid)]
      (prn {:graph graph
            :e eid
            :exists (some? ent)
            :uuid (some-> (first (filter #(= eid (:e %)) (d/datoms db :avet :block/uuid))) :v str)
            :attrs (mapv (fn [dat] [(:a dat) (str (:v dat))]) (d/datoms db :eavt eid))}))))
