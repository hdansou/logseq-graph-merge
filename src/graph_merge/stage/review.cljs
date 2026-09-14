(ns graph-merge.stage.review
  "S9 · The Graph Merge review page: each source's config.edn and custom.css as code blocks,
   followed by the merge report (R12)."
  (:require [cljs.pprint :as pprint]
            [graph-merge.uuid :as guuid]))

(def ^:private title "Graph Merge")

(defn- code-block [lang content]
  {:block/title (or content "")
   :build/properties {:logseq.property.node/display-type :code
                      :logseq.property.code/lang lang}})

(defn- file-content [files path]
  (some #(when (= path (:file/path %)) (:file/content %)) files))

(defn- source-block [[graph files]]
  {:block/title graph
   :build/children [(code-block "clojure" (file-content files "logseq/config.edn"))
                    (code-block "css" (file-content files "logseq/custom.css"))]})

(defn- report-block [sections]
  {:block/title "Merge report"
   :build/children (mapv (fn [[section data]]
                           {:block/title section
                            :build/children [(code-block "clojure" (with-out-str (pprint/pprint data)))]})
                         sections)})

(defn graph-merge-page
  "`graph-files` is [[graph files] ...] in source order, `report` is [[section data] ...] and
   `taken-titles` holds normalized titles of top-level source pages."
  [{:keys [graph-files report taken-titles]}]
  (let [page-title (if (taken-titles "graph merge") (str title " (tool)") title)]
    {:page {:block/title page-title
            :block/uuid (guuid/derived-uuid "logseq-graph-merge" page-title)
            :build/keep-uuid? true}
     :blocks (conj (mapv source-block graph-files) (report-block report))}))
