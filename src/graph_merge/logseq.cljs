(ns graph-merge.logseq
  "Thin wrapper over the `logseq` CLI. Every call returns the :data of the command's EDN output,
   or throws with the command and its stderr.
   Graph-scoped commands silently create a missing graph (trap 14): only call them with names
   that `graphs` has confirmed."
  (:require ["child_process" :as child-process]
            [clojure.string :as string]
            [graph-merge.io :as io]))

(def ^:private timeout-ms "600000")

(defn- run!
  [& args]
  (let [argv (concat args ["-o" "edn" "--timeout-ms" timeout-ms])
        result (child-process/spawnSync "logseq" (clj->js argv)
                                        #js {:encoding "utf8" :maxBuffer (* 1024 1024 1024)})
        command (str "logseq " (string/join " " args))]
    (when (or (.-error result) (not= 0 (.-status result)))
      (throw (ex-info (str command " failed: "
                           (or (some-> (.-error result) .-message)
                               (string/trim (str (.-stderr result) (.-stdout result)))))
                      {:command command :status (.-status result)})))
    (let [output (io/read-edn (.-stdout result))]
      (when-not (= :ok (:status output))
        (throw (ex-info (str command " did not succeed") {:command command :output output})))
      (:data output))))

(defn graphs []
  (set (:graphs (run! "graph" "list"))))

(defn schema-version [graph]
  (:logseq.kv/schema-version (run! "graph" "info" "--graph" graph)))

(defn export-graph! [graph file]
  (run! "graph" "export" "--graph" graph "--type" "edn" "--file" file
        "--edn-options" "{:export-type :graph-human :include-timestamps? true}"))

(defn query [graph query-edn]
  (:result (run! "query" "--graph" graph "--query" query-edn)))

(defn import-graph! [graph file]
  (run! "graph" "import" "--graph" graph "--type" "edn" "--input" file))

(defn validate-graph! [graph]
  (run! "graph" "validate" "--graph" graph))
