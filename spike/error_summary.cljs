(ns error-summary
  "Spike probe: import an export into an in-memory graph and summarize validation errors by kind,
   with one example entity per kind. Usage: nbb-logseq -cp src spike/error_summary.cljs <export.edn>"
  (:require [cljs.pprint :refer [pprint]]
            [datascript.core :as d]
            [graph-merge.io :as io]
            [logseq.db.frontend.validate :as db-validate]
            [logseq.db.sqlite.export :as sqlite-export]))

(let [export (io/read-edn-file (first *command-line-args*))
      conn (sqlite-export/create-conn)
      txs (sqlite-export/build-import export @conn {})
      db (:db-after (d/with @conn (sqlite-export/import-tx-data txs)))
      errors (:errors (db-validate/validate-local-db! db))
      kind (fn [{:keys [dispatch-key errors]}] [dispatch-key (sort (keys errors))])]
  (pprint (for [[k group] (group-by kind errors)]
            {:kind k
             :count (count group)
             :example (let [{:keys [entity errors]} (first group)
                            e (d/entity db (:db/id entity))]
                        {:errors errors
                         :entity (select-keys (d/touch e) [:block/uuid :block/title :block/page :block/parent
                                                           :logseq.property/value :logseq.property/created-from-property
                                                           :block/tags :block/order])
                         :page-title (:block/title (:block/page e))
                         :from-property (:db/ident (:logseq.property/created-from-property e))})})))
