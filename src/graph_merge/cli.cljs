(ns graph-merge.cli
  "Command-line arguments for the merge command."
  (:require ["os" :as os]
            ["path" :as path]
            [clojure.string :as string]))

(defn- validate [{:keys [sources dest config-from] :as opts}]
  (cond
    (empty? sources) {:error "--sources is required: a comma-separated list of graphs, first one wins conflicts"}
    (string/blank? dest) {:error "--dest is required: the name of the new graph to create"}
    (some #{dest} sources) {:error (str "--dest must be a new graph, but \"" dest "\" is also a source")}
    (and config-from (not (some #{config-from} sources)))
    {:error (str "--config-from must be one of the sources, got \"" config-from "\"")}
    :else (merge {:config-from nil
                  :out (str "out/" dest)
                  :root-dir (path/join (os/homedir) "logseq")}
                 opts)))

(defn parse-args
  "Returns the options map, or {:error message} explaining what to fix."
  [args]
  (loop [[arg value & more :as remaining] args
         opts {:dry-run? false}]
    (case arg
      nil (validate opts)
      "--dry-run" (recur (rest remaining) (assoc opts :dry-run? true))
      "--sources" (recur more (assoc opts :sources (->> (string/split (str value) #",")
                                                        (map string/trim)
                                                        (remove string/blank?)
                                                        vec)))
      "--dest" (recur more (assoc opts :dest value))
      "--config-from" (recur more (assoc opts :config-from value))
      "--out" (recur more (assoc opts :out value))
      "--root-dir" (recur more (assoc opts :root-dir value))
      {:error (str "Unknown option: " arg)})))
