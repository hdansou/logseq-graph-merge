(ns probe-rebuild-diff
  "Spike probe (read-only): compare the [e a v] facts of two graphs and print what
   the rebuild dropped or added.
   Usage: pnpm exec nbb-logseq -cp src spike/probe_rebuild_diff.cljs <dir-a> <graph-a> <dir-b> <graph-b>"
  (:require [clojure.set :as set]
            [datascript.core :as d]
            [logseq.db.common.sqlite-cli :as sqlite-cli]))

(let [[dir-a graph-a dir-b graph-b] *command-line-args*
      facts (fn [db] (into #{} (map (fn [dat] [(:e dat) (:a dat) (str (:v dat))])) (d/datoms db :eavt)))
      db-a @(sqlite-cli/open-db! dir-a graph-a)
      db-b @(sqlite-cli/open-db! dir-b graph-b)
      a (facts db-a)
      b (facts db-b)
      only-a (set/difference a b)
      only-b (set/difference b a)]
  (prn {:only-in-source (count only-a) :only-in-rebuild (count only-b)})
  (doseq [f (sort-by first only-a)] (prn [:dropped f]))
  (doseq [f (sort-by first only-b)] (prn [:added f])))
