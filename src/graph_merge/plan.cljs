(ns graph-merge.plan
  "Pure merge planner. Takes source graph data in precedence order and returns the merged
   :graph-human export map, the report and the asset files to copy. No I/O happens here.
   See docs/merge-workflow.md §2 for the stages."
  (:require [graph-merge.blocks :as blocks]
            [graph-merge.stage.assets :as assets]
            [graph-merge.stage.emit :as emit]
            [graph-merge.stage.idents :as idents]
            [graph-merge.stage.normalize :as normalize]
            [graph-merge.stage.ontology :as ontology]
            [graph-merge.stage.pages :as pages]
            [graph-merge.stage.refs :as refs]
            [graph-merge.stage.review :as review]
            [graph-merge.stage.uuids :as uuids]
            [graph-merge.stage.values :as values]))

(defn- block-count [pages-and-blocks]
  (blocks/count-blocks (mapcat :blocks pages-and-blocks)))

(defn- top-level-titles [pages-and-blocks]
  (set (for [{:keys [page]} pages-and-blocks
             :when (and (:block/title page) (not (:block/parent page)))]
         (ontology/normalize-title (:block/title page)))))

(defn plan
  "Merges `sources` in precedence order (first source wins). Each source is
   {:graph :export (:graph-human map) :page-rows [...] :asset-rows [...]}.
   Options: :config-from, the graph whose logseq/config.edn and custom.css are copied.
   Returns {:export :report :asset-files}."
  ([sources] (plan sources {}))
  ([sources {:keys [config-from]}]
   (let [normalized (mapv (comp values/clean-values normalize/normalize-source) sources)
         rekeyed (uuids/rekey-collisions normalized)
         props (ontology/unify-properties (:sources rekeyed))
         classes (ontology/unify-classes (:sources rekeyed) (:ident-maps props))
         renamed (idents/rewrite-idents {:sources (:sources rekeyed)
                                         :properties (:properties props)
                                         :classes (:classes classes)
                                         :property-ident-maps (:ident-maps props)
                                         :class-ident-maps (:ident-maps classes)})
         deduped (assets/dedupe-assets (:sources renamed))
         merged-pages (pages/merge-pages {:sources (:sources deduped)
                                          :classes (:classes renamed)
                                          :uuid-map (merge (:uuid-map props) (:uuid-map classes))})
         merged (refs/rewrite-refs {:pages-and-blocks (:pages-and-blocks merged-pages)
                                    :properties (:properties renamed)
                                    :classes (:classes renamed)}
                                   [(:uuid-map props) (:uuid-map classes)
                                    (:uuid-map deduped) (:uuid-map merged-pages)])
         report {:sources (mapv :graph sources)
                 :counts {:sources (into {} (map (fn [{:keys [graph export]}]
                                                   [graph {:pages (count (:pages-and-blocks export))
                                                           :blocks (block-count (:pages-and-blocks export))}]))
                                     normalized)
                          :merged {:pages (count (:pages-and-blocks merged))
                                   :blocks (block-count (:pages-and-blocks merged))
                                   :properties (count (:properties merged))
                                   :classes (count (:classes merged))
                                   :assets (count (:files deduped))}}
                 :skipped (vec (mapcat :skipped normalized))
                 :value-fixes (vec (mapcat :value-fixes normalized))
                 :uuid-rekeys (:rekeyed rekeyed)
                 :property-renames (:renames props)
                 :schema-differences (into (:diffs props) (:diffs classes))
                 :extends-cycles (:cycles classes)
                 :idents-in-text (:strings-with-idents renamed)
                 :asset-dedupes (:dedupes deduped)
                 :page-merges (:page-merges merged-pages)
                 :definition-page-merges (:definition-page-merges merged-pages)
                 :favorite-dedupes (:favorite-dedupes merged-pages)
                 :re-rooted (:re-rooted merged-pages)}
         review-page (review/graph-merge-page
                      {:graph-files (mapv (juxt :graph :graph-files) normalized)
                       :report (mapv (fn [k] [(name k) (get report k)]) (keys report))
                       :taken-titles (top-level-titles (:pages-and-blocks merged))})]
     {:export (emit/emit (-> merged
                             (update :pages-and-blocks conj review-page)
                             (assoc :graph-files (some #(when (= config-from (:graph %)) (:graph-files %))
                                                       normalized))))
      :report report
      :asset-files (:files deduped)})))
