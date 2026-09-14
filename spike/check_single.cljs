(ns check-single
  "Spike probe: validate-export on one raw source export (no merge) to tell upstream issues from ours."
  (:require ["fs" :as fs] [cljs.reader :as reader] [logseq.db.sqlite.export :as sqlite-export]))
(let [g (first *command-line-args*)
      e (reader/read-string {:default tagged-literal} (str (fs/readFileSync (if (re-find #"/" g) g (str "spike/out/" g ".graph-human.edn")))))]
  (prn g (or (:error (sqlite-export/validate-export e)) :ok)))
