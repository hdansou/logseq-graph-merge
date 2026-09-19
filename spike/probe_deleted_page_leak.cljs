(ns probe-deleted-page-leak
  "Spike probe (read-only, on copies): take a page's blocks from an OLD snapshot and
   check, in a NEW snapshot, which of them are gone cleanly and which left a stale
   :block/uuid index entry behind.
   Usage: pnpm exec nbb-logseq -cp src spike/probe_deleted_page_leak.cljs <dir> <old-graph> <new-graph> <page-eid>"
  (:require [datascript.core :as d]
            [logseq.db.common.sqlite-cli :as sqlite-cli]))

(let [[dir old-graph new-graph page-eid] *command-line-args*
      page-eid (js/parseInt page-eid)
      old-db @(sqlite-cli/open-db! dir old-graph)
      new-db @(sqlite-cli/open-db! dir new-graph)
      members (into [page-eid]
                    (comp (filter #(= page-eid (:v %))) (map :e))
                    (d/datoms old-db :eavt))
      members (distinct members)
      classify (fn [e]
                 (let [in-index? (some #(= e (:e %)) (d/datoms new-db :avet :block/uuid))
                       entity? (some? (d/entity new-db e))]
                   (cond entity? :still-there
                         in-index? :stale-index-entry
                         :else :gone-cleanly)))
      by-class (group-by classify members)]
  (prn {:old old-graph :new new-graph :page page-eid
        :members (count members)
        :counts (into {} (map (fn [[k v]] [k (count v)]) by-class))
        :stale (vec (:stale-index-entry by-class))
        :still-there (vec (:still-there by-class))}))
