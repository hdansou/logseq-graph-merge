(ns graph-merge.main
  "The merge command: preflight, extract, plan, validate, write, verify.
   See docs/merge-workflow.md §1."
  (:require ["fs" :as fs]
            ["path" :as path]
            [graph-merge.cli :as cli]
            [graph-merge.io :as io]
            [graph-merge.logseq :as logseq]
            [graph-merge.plan :as plan]
            [graph-merge.verify :as verify]
            [logseq.common.graph-dir :as graph-dir]
            [logseq.db.sqlite.export :as sqlite-export]))

(def usage
  "Usage: pnpm merge --sources <g1,g2,...> --dest <new-graph> [--dry-run] [--config-from <g>] [--out <dir>] [--root-dir <dir>]
  --sources      graphs to merge; the first one listed wins conflicts
  --dest         name of the new graph to create (must not exist)
  --dry-run      plan and validate only; writes merged.edn and report.edn, touches no graph
  --config-from  copy this source's logseq/config.edn and custom.css
  --out          working directory (default out/<dest>)
  --root-dir     logseq CLI root dir (default ~/logseq)")

(def ^:private page-rows-query
  "[:find [(pull ?p [:block/uuid :block/title :block/created-at :block/order {:block/parent [:block/uuid]}]) ...] :where [?p :block/name]]")

(def ^:private asset-rows-query
  "[:find [(pull ?a [:block/uuid :logseq.property.asset/checksum :logseq.property.asset/type]) ...] :where [?a :block/tags :logseq.class/Asset]]")

(def ^:private block-counts-query
  "[:find ?u (count ?b) :where [?b :block/page ?p] [?p :block/uuid ?u] [(missing? $ ?b :logseq.property/created-from-property)]]")

(defn- step [& parts]
  (println (apply str "• " parts)))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- preflight! [{:keys [sources dest]}]
  (let [existing (logseq/graphs)
        missing (vec (remove existing sources))]
    (when (seq missing)
      (fail! (str "Source graphs not found: " (pr-str missing)) {:missing missing}))
    (when (existing dest)
      (fail! (str "Destination graph \"" dest "\" already exists; choose a new name or remove it first") {}))
    (let [versions (into {} (map (juxt identity logseq/schema-version)) sources)]
      (when (< 1 (count (set (vals versions))))
        (fail! "Sources have different schema versions; open each in the same Logseq version first"
               {:schema-versions versions}))
      (step "Preflight ok: " (count sources) " sources, schema " (pr-str (first (vals versions)))
            ", logseq CLI " (logseq/cli-revision)))))

(defn- extract! [{:keys [sources out]}]
  (mapv (fn [graph]
          (let [file (path/join out "sources" (str (graph-dir/encode-graph-dir-name graph) ".edn"))]
            (fs/mkdirSync (path/dirname file) #js {:recursive true})
            (logseq/export-graph! graph file)
            (let [source {:graph graph
                          :export (io/read-edn-file file)
                          :page-rows (mapv (fn [r] {:uuid (:block/uuid r) :title (:block/title r)
                                                    :created-at (:block/created-at r)
                                                    :parent-uuid (get-in r [:block/parent :block/uuid])
                                                    :order (:block/order r)})
                                           (logseq/query graph page-rows-query))
                          :asset-rows (mapv (fn [r] {:uuid (:block/uuid r)
                                                     :checksum (:logseq.property.asset/checksum r)
                                                     :type (:logseq.property.asset/type r)})
                                            (logseq/query graph asset-rows-query))}]
              (when (verify/timestamps-missing? (:export source))
                (fail! (str "Export of " graph " has no timestamps, so pages can't be matched. "
                            "The CLI build may ignore :include-timestamps? in the shape sent; "
                            "see requirements trap 18.")
                       {:graph graph}))
              (step "Extracted " graph ": " (count (get-in source [:export :pages-and-blocks])) " pages, "
                    (count (:asset-rows source)) " assets")
              source)))
        sources))

(defn- validate! [{:keys [root-dir]} {:keys [export asset-files]}]
  (when-let [error (:error (sqlite-export/validate-export export))]
    (fail! (str "Merged export is invalid, nothing was written: " error) {}))
  (let [problems (verify/asset-problems asset-files
                                        (fn [{:keys [graph uuid type]}]
                                          (io/sha256-file (io/asset-path root-dir graph uuid type))))]
    (when (seq problems)
      (fail! "Asset files are missing or changed, nothing was written" {:problems problems})))
  (step "Validated merged export and " (count asset-files) " asset files"))

(defn- write! [{:keys [dest out root-dir]} {:keys [asset-files]}]
  (logseq/import-graph! dest (path/join out "merged.edn"))
  (let [assets-dir (path/join (io/graph-dir root-dir dest) "assets")]
    (fs/mkdirSync assets-dir #js {:recursive true})
    (doseq [{:keys [graph uuid type]} asset-files]
      (fs/copyFileSync (io/asset-path root-dir graph uuid type) (io/asset-path root-dir dest uuid type))))
  (step "Imported into " dest " and copied " (count asset-files) " asset files"))

(defn- verify! [{:keys [dest out root-dir]} {:keys [export asset-files]}]
  (let [actual (into {} (map vec) (logseq/query dest block-counts-query))
        shortfalls (verify/shortfalls (verify/expected-block-counts export) actual)
        copied (verify/asset-problems asset-files
                                      (fn [{:keys [uuid type]}] (io/sha256-file (io/asset-path root-dir dest uuid type))))]
    (io/write-edn-file (path/join out "verify.edn") {:shortfalls shortfalls :asset-problems copied})
    (when (or (seq shortfalls) (seq copied))
      (fail! (str "Destination doesn't match the merge (graph import can fail silently); see " out "/verify.edn")
             {:shortfalls (count shortfalls) :asset-problems (count copied)}))
    (logseq/validate-graph! dest)
    (step "Verified " dest ": every page has its blocks, assets match, graph validate passed")))

(defn run [{:keys [dest out dry-run? config-from] :as opts}]
  (preflight! opts)
  (let [result (plan/plan (extract! opts) {:config-from config-from})
        {:keys [counts]} (:report result)]
    (io/write-edn-file (path/join out "merged.edn") (:export result))
    (io/write-edn-file (path/join out "report.edn") (:report result))
    (step "Planned: " (pr-str (:merged counts)) " -> " out "/merged.edn, report.edn")
    (validate! opts result)
    (if dry-run?
      (step "Dry run: no graph was written")
      (do (write! opts result)
          (verify! opts result)
          (step "Done: " dest)))))

(defn -main [& args]
  (let [opts (cli/parse-args args)]
    (if-let [error (:error opts)]
      (do (println error) (println usage) (set! (.-exitCode js/process) 2))
      (try
        (run opts)
        (catch :default e
          (println "Merge failed:" (ex-message e))
          (some-> (ex-data e) not-empty prn)
          (set! (.-exitCode js/process) 1))))))
