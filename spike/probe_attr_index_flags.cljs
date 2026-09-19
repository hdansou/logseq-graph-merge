(ns probe-attr-index-flags
  "Spike probe (read-only): for the db-ident attributes that the code AVET-scans,
   report whether this graph's stored schema marks them :db/index true.
   Usage: pnpm exec nbb-logseq -cp src spike/probe_attr_index_flags.cljs <graphs-dir> <graph> [ident]..."
  (:require [datascript.core :as d]
            [logseq.db.common.sqlite-cli :as sqlite-cli]))

(let [[dir graph & idents] *command-line-args*
      db @(sqlite-cli/open-db! dir graph)
      idents (if (seq idents)
               (map keyword idents)
               [:logseq.property/deleted-at :logseq.property/hide? :logseq.property/built-in?
                :logseq.property/created-by-ref :logseq.property/deleted-by-ref])]
  (doseq [ident idents]
    (let [ent (d/entity db ident)]
      (prn {:graph graph
            :ident ident
            :exists (some? ent)
            :db-index (:db/index ent)
            :schema-index (get-in (:schema db) [ident :db/index])
            :datoms (count (d/datoms db :aevt ident))}))))
