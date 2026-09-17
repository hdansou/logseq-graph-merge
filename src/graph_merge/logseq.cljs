(ns graph-merge.logseq
  "Thin wrapper over the `logseq` CLI. Every call returns the :data of the command's EDN output,
   or throws with the command and its stderr.
   Graph-scoped commands silently create a missing graph (trap 14): only call them with names
   that `graphs` has confirmed."
  (:require ["child_process" :as child-process]
            ["path" :as path]
            [clojure.string :as string]
            [graph-merge.io :as io]))

(def ^:private timeout-ms "600000")

(def ^:private app-env-var
  "Points the tool at one Logseq desktop build instead of the managed `logseq` wrapper,
   which belongs to whichever desktop app started last (trap 15)."
  "GRAPH_MERGE_LOGSEQ_APP")

(def export-options
  "Options for `graph export`. `:include-timestamps?` is sent twice on purpose: b09316a reads it
   at the top level, while 6bf8fe7-dirty only reads it under :graph-options. R2a needs the
   timestamps to match pages by (title, created-at)."
  {:export-type :graph-human
   :include-timestamps? true
   :graph-options {:include-timestamps? true}})

(defn command
  "The process to spawn for CLI `args`: the managed `logseq` wrapper, or, given a desktop
   app path, the same Electron command that wrapper would run for that app."
  [app args]
  (if (string/blank? app)
    {:file "logseq" :args (vec args) :env nil}
    {:file (path/join app "Contents" "MacOS" "Logseq")
     :args (into [(path/join app "Contents" "Resources" "app.asar" "js" "logseq-cli.js")] args)
     :env {"ELECTRON_RUN_AS_NODE" "1"}}))

(defn parse-revision [version-output]
  (second (re-find #"Revision:\s*(\S+)" (str version-output))))

(defn- spawn [args]
  (let [{:keys [file args env]} (command (aget js/process.env app-env-var) args)]
    (child-process/spawnSync file (clj->js args)
                             #js {:encoding "utf8"
                                  :maxBuffer (* 1024 1024 1024)
                                  :env (js/Object.assign #js {} js/process.env (clj->js env))})))

(defn cli-revision
  "The revision of the CLI the tool will run, e.g. \"b09316a\"."
  []
  (parse-revision (.-stdout (spawn ["--version"]))))

(defn- run!
  [& args]
  (let [result (spawn (concat args ["-o" "edn" "--timeout-ms" timeout-ms]))
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
        "--edn-options" (pr-str export-options)))

(defn query [graph query-edn]
  (:result (run! "query" "--graph" graph "--query" query-edn)))

(defn import-graph! [graph file]
  (run! "graph" "import" "--graph" graph "--type" "edn" "--input" file))

(defn validate-graph! [graph]
  (run! "graph" "validate" "--graph" graph))
