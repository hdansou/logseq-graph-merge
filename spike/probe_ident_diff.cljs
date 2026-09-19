(ns probe-ident-diff
  "Spike probe (read-only): compare the :db/ident entities of two graphs, to find
   built-ins that one graph has and the other is missing.
   Usage: pnpm exec nbb-logseq -cp src spike/probe_ident_diff.cljs <dir-a> <graph-a> <dir-b> <graph-b>"
  (:require [clojure.set :as set]
            [datascript.core :as d]
            [logseq.db.common.sqlite-cli :as sqlite-cli]))

(let [[dir-a graph-a dir-b graph-b] *command-line-args*
      idents (fn [db] (into #{} (map :v) (d/datoms db :aevt :db/ident)))
      db-a @(sqlite-cli/open-db! dir-a graph-a)
      db-b @(sqlite-cli/open-db! dir-b graph-b)
      a (idents db-a)
      b (idents db-b)]
  (prn {:a graph-a :a-count (count a) :b graph-b :b-count (count b)
        :only-in-a (count (set/difference a b))
        :only-in-b (count (set/difference b a))})
  (prn {:missing-from-b (vec (sort (set/difference a b)))})
  (prn {:missing-from-a (vec (sort (set/difference b a)))}))
