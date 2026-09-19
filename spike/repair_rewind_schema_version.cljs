(ns repair-rewind-schema-version
  "Spike repair (WRITES to the graph it is given — always run it on a copy first):
   set :logseq.kv/schema-version back to a chosen version so the worker re-runs the
   migrations from there on the next open.
   Usage: pnpm exec nbb-logseq -cp src spike/repair_rewind_schema_version.cljs <graphs-dir> <graph> <major> <minor>"
  (:require [datascript.core :as d]
            [logseq.db.common.sqlite-cli :as sqlite-cli]
            [logseq.db.common.sqlite :as common-sqlite]))

(let [[dir graph major minor] *command-line-args*
      db-path (second (common-sqlite/get-db-full-path dir graph))
      {:keys [conn]} (sqlite-cli/open-sqlite-datascript! db-path)
      before (:kv/value (d/entity @conn :logseq.kv/schema-version))
      target {:major (js/parseInt major) :minor (js/parseInt minor)}]
  (d/transact! conn [{:db/ident :logseq.kv/schema-version :kv/value target}])
  (prn {:graph graph :was before :now (:kv/value (d/entity @conn :logseq.kv/schema-version))}))
