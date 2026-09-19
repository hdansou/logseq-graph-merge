(ns probe-schema-version
  "Spike probe (read-only): print a graph's stored schema-version kv entries.
   Usage: pnpm exec nbb-logseq -cp src spike/probe_schema_version.cljs <graphs-dir> <graph>"
  (:require [datascript.core :as d]
            [logseq.db.common.sqlite-cli :as sqlite-cli]))

(let [[dir graph] *command-line-args*
      db @(sqlite-cli/open-db! dir graph)
      kv (fn [ident] (:kv/value (d/entity db ident)))]
  (prn {:graph graph
        :schema-version (kv :logseq.kv/schema-version)
        :initial-schema-version (kv :logseq.kv/graph-initial-schema-version)
        :git-sha (kv :logseq.kv/graph-git-sha)}))
