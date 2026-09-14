(ns check-ontology
  "Spike probe: run S3 unify-properties over real :graph-human exports in the given order."
  (:require ["fs" :as fs]
            [cljs.reader :as reader]
            [cljs.pprint :refer [pprint]]
            [graph-merge.stage.ontology :as ontology]))

(let [sources (mapv (fn [g] {:graph g :export (reader/read-string {:default tagged-literal}
                                                                  (str (fs/readFileSync (str "spike/out/" g ".graph-human.edn"))))})
                    *command-line-args*)
      {:keys [properties ident-maps uuid-map renames diffs]} (ontology/unify-properties sources)
      classes (ontology/unify-classes sources ident-maps)]
  (pprint {:input (into {} (map (fn [s] [(:graph s) (count (get-in s [:export :properties]))]) sources))
           :merged (count properties)
           :mapped (update-vals ident-maps count)
           :closed-value-merges (count uuid-map)
           :renames (map (juxt :graph :title :new-ident) renames)
           :diffs diffs
           :untitled (keep (fn [[k v]] (when-not (:block/title v) k)) properties)
           :classes {:input (into {} (map (fn [s] [(:graph s) (count (get-in s [:export :classes]))]) sources))
                     :merged (count (:classes classes))
                     :mapped (update-vals (:ident-maps classes) count)
                     :company (some (fn [[k v]] (when (= "company" (:block/title v)) [k v])) (:classes classes))
                     :diffs (:diffs classes)
                     :cycles (:cycles classes)}}))
