(ns check-orphan-datoms
  "Spike probe (read-only, on a copy): replicate what the worker's ensure-canonical-revisions!
   does at startup, to find why (:db/id (d/entity db e)) comes back nil.
   Usage: pnpm exec nbb-logseq -cp src spike/check_orphan_datoms.cljs <graphs-dir> <graph-name>"
  (:require [datascript.core :as d]
            [logseq.db.common.sqlite-cli :as sqlite-cli]))

(let [[dir graph] *command-line-args*
      conn (sqlite-cli/open-db! dir graph)
      db @conn
      uuid-datoms (vec (d/datoms db :avet :block/uuid))
      entity-of (fn [datom] (d/entity db (:e datom)))
      nil-entity (filterv #(nil? (entity-of %)) uuid-datoms)
      nil-db-id (filterv #(nil? (:db/id (entity-of %))) uuid-datoms)
      no-tx-id (filterv #(not (nat-int? (:block/tx-id (entity-of %)))) uuid-datoms)]
  (prn {:uuid-datoms (count uuid-datoms)
        :entity-nil (count nil-entity)
        :db-id-nil (count nil-db-id)
        :missing-tx-id (count no-tx-id)
        :examples (mapv (fn [dat] {:e (:e dat) :uuid (str (:v dat))}) (take 5 nil-db-id))}))

;; Detail pass: what, if anything, exists for those entity ids?
(let [[dir graph] *command-line-args*
      conn (sqlite-cli/open-db! dir graph)
      db @conn
      orphans (filterv #(nil? (d/entity db (:e %))) (d/datoms db :avet :block/uuid))]
  (doseq [datom orphans]
    (prn {:e (:e datom)
          :uuid (str (:v datom))
          :eavt-datoms (mapv (fn [d] [(:a d) (str (:v d))]) (d/datoms db :eavt (:e datom)))
          :aevt-datoms (count (d/datoms db :aevt :block/uuid (:e datom)))})))

;; Does the failing tx-id in the error match (inc (:max-tx db))?
(let [[dir graph] *command-line-args*
      db @(sqlite-cli/open-db! dir graph)]
  (prn {:max-tx (:max-tx db) :inc-max-tx (inc (:max-tx db))}))
