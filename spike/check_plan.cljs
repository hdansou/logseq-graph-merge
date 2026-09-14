(ns check-plan
  "Spike probe: run the full planner over real exports + live identity rows, then validate the
   merged export with Logseq's own validate-export (the D4 gate), without touching any graph.
   Usage: pnpm exec nbb-logseq -cp src spike/check_plan.cljs <graph> <graph> ..."
  (:require ["fs" :as fs]
            [cljs.pprint :refer [pprint]]
            [cljs.reader :as reader]
            [graph-merge.plan :as plan]
            [logseq.db.sqlite.export :as sqlite-export]))

(defn- read-edn [path]
  (reader/read-string {:default tagged-literal} (str (fs/readFileSync path))))

(defn- rows [path]
  (-> (read-edn path) :data :result))

(defn- source [graph]
  {:graph graph
   :export (read-edn (str "spike/out/" graph ".graph-human.edn"))
   :page-rows (mapv (fn [r] {:uuid (:block/uuid r) :title (:block/title r) :created-at (:block/created-at r)
                             :parent-uuid (get-in r [:block/parent :block/uuid]) :order (:block/order r)})
                    (rows (str "spike/out/" graph ".page-rows.edn")))
   :asset-rows (mapv (fn [r] {:uuid (:block/uuid r) :checksum (:logseq.property.asset/checksum r)
                              :type (:logseq.property.asset/type r)})
                     (rows (str "spike/out/" graph ".asset-rows.edn")))})

(let [started (js/Date.now)
      {:keys [export report asset-files]} (plan/plan (mapv source *command-line-args*))
      planned (js/Date.now)
      validation (sqlite-export/validate-export export)]
  (fs/writeFileSync "spike/out/merged.edn" (pr-str export))
  (pprint {:plan-ms (- planned started)
           :validate-ms (- (js/Date.now) planned)
           :counts (:counts report)
           :asset-files (count asset-files)
           :report-sizes (update-vals (dissoc report :counts :sources) count)
           :validation (if-let [error (:error validation)] {:error error} :ok)}))
