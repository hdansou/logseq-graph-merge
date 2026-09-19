(ns repair-rebuild-indexes
  "Spike repair (writes a NEW sqlite file; never touches the source): read every :eavt
   datom out of a graph and replay it into a fresh storage-backed conn, so all indexes
   are rebuilt from the same facts and stale :avet entries cannot survive.
   Usage: pnpm exec nbb-logseq -cp src spike/repair_rebuild_indexes.cljs <graphs-dir> <graph> <out-db-path>"
  (:require [datascript.core :as d]
            [logseq.db.common.sqlite-cli :as sqlite-cli]))

(let [[dir graph out-path] *command-line-args*
      src-db @(sqlite-cli/open-db! dir graph)
      datoms (vec (d/datoms src-db :eavt))
      {:keys [conn]} (sqlite-cli/open-sqlite-datascript! out-path)
      tx (mapv (fn [dat] [:db/add (:e dat) (:a dat) (:v dat)]) datoms)]
  (prn {:source-datoms (count datoms)
        :source-uuid-index (count (d/datoms src-db :avet :block/uuid))})
  (doseq [chunk (partition-all 2000 tx)]
    (d/transact! conn (vec chunk)))
  (let [out @conn
        stale (filterv #(nil? (d/entity out (:e %))) (d/datoms out :avet :block/uuid))]
    (prn {:out-datoms (count (d/datoms out :eavt))
          :out-uuid-index (count (d/datoms out :avet :block/uuid))
          :stale-index-entries (count stale)})))
