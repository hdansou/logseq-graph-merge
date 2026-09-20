(ns graph-merge.cli
  "Command-line arguments for the merge command."
  (:require ["os" :as os]
            ["path" :as path]
            [clojure.string :as string]))

(def ^:private value-options
  "Options that take the next argument as their value."
  #{"--sources" "--dest" "--config-from" "--out" "--root-dir"})

(defn- first-duplicate
  "The first value listed more than once, in the order given."
  [values]
  (some (fn [[value n]] (when (< 1 n) value))
        (map (juxt identity (frequencies values)) values)))

(defn- swallowed-option
  "A value option takes the next argument whatever it is, so `--sources --dest d` would
   merge a graph called \"--dest\" and drop `d`. A trailing option with no value at all is
   left to `validate`, which already explains what each option is for."
  [arg value]
  (when (and (value-options arg) (string? value) (string/starts-with? value "--"))
    {:error (str arg " needs a value, got the option \"" value "\"")}))

(defn- validate [{:keys [sources dest config-from] :as opts}]
  (let [repeated (first-duplicate sources)]
    (cond
      (empty? sources) {:error "--sources is required: a comma-separated list of graphs, first one wins conflicts"}
      repeated {:error (str "--sources must not repeat a graph, but \"" repeated "\" is listed twice")}
      (string/blank? dest) {:error "--dest is required: the name of the new graph to create"}
      (some #{dest} sources) {:error (str "--dest must be a new graph, but \"" dest "\" is also a source")}
      (and config-from (not (some #{config-from} sources)))
      {:error (str "--config-from must be one of the sources, got \"" config-from "\"")}
      :else (merge {:config-from nil
                    :out (str "out/" dest)
                    :root-dir (path/join (os/homedir) "logseq")}
                   opts))))

(defn parse-args
  "Returns the options map, or {:error message} explaining what to fix."
  [args]
  (loop [[arg value & more :as remaining] args
         opts {:dry-run? false}]
    (or
     (swallowed-option arg value)
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
       {:error (str "Unknown option: " arg)}))))
