(ns graph-merge.logseq-test
  (:require [cljs.test :refer [deftest is testing]]
            [graph-merge.logseq :as logseq]))

(deftest command-uses-the-managed-logseq-wrapper-by-default
  (is (= {:file "logseq" :args ["graph" "list"] :env nil}
         (logseq/command nil ["graph" "list"]))))

(deftest command-can-target-one-desktop-build
  (testing "the same command the managed ~/.local/bin/logseq wrapper runs, for a chosen app (trap 15)"
    (is (= {:file "/Applications/Logseq.app/Contents/MacOS/Logseq"
            :args ["/Applications/Logseq.app/Contents/Resources/app.asar/js/logseq-cli.js" "graph" "list"]
            :env {"ELECTRON_RUN_AS_NODE" "1"}}
           (logseq/command "/Applications/Logseq.app/" ["graph" "list"])))))

(deftest revision-is-read-from-version-output
  (is (= "f7362f0-dirty"
         (logseq/parse-revision "Build time: 2026-08-26T14:58:45.214Z\nRevision: f7362f0-dirty\n")))
  (is (nil? (logseq/parse-revision "unexpected output"))))

(deftest export-options-ask-for-timestamps-in-both-shapes
  (testing "b09316a reads :include-timestamps? at the top level; 6bf8fe7-dirty only reads it
            under :graph-options. Sending both keeps every build exporting timestamps,
            which R2a needs to match pages by (title, created-at)."
    (is (= {:export-type :graph-human
            :include-timestamps? true
            :graph-options {:include-timestamps? true}}
           logseq/export-options))))
