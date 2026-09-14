(ns check-identity
  "Spike probe: run S1 normalize on a real :graph-human export plus live page identity rows.
   Usage: pnpm exec nbb-logseq -cp src spike/check_identity.cljs <export.edn> <page-rows.edn>"
  (:require ["fs" :as fs]
            [cljs.reader :as reader]
            [graph-merge.stage.normalize :as normalize]))

(defn- read-edn [path] (reader/read-string {:default tagged-literal} (str (fs/readFileSync path))))

(let [[export-path rows-path] *command-line-args*
      rows (->> (read-edn rows-path) :data :result
                (mapv (fn [r] {:uuid (:block/uuid r) :title (:block/title r) :created-at (:block/created-at r)
                               :parent-uuid (get-in r [:block/parent :block/uuid]) :order (:block/order r)})))
      out (try (normalize/normalize-source {:graph "probe" :export (read-edn export-path) :page-rows rows})
               (catch :default e {:error (ex-message e) :data (ex-data e)}))]
  (if (:error out)
    (prn out)
    (let [pages (map :page (-> out :export :pages-and-blocks))]
      (prn {:pages (count pages)
            :with-uuid (count (filter :block/uuid pages))
            :with-parent (count (filter :graph-merge/parent-uuid pages))
            :skipped (frequencies (map :reason (:skipped out)))}))))
