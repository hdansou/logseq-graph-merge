(ns graph-merge.cli-test
  (:require ["os" :as os]
            ["path" :as path]
            [cljs.test :refer [deftest is testing]]
            [graph-merge.cli :as cli]))

(deftest parses-a-merge-command
  (is (= {:sources ["CRM-Simple" "GTD-02"]
          :dest "merge-e2e-01"
          :dry-run? false
          :config-from nil
          :out "out/merge-e2e-01"
          :root-dir (path/join (os/homedir) "logseq")}
         (cli/parse-args ["--sources" "CRM-Simple, GTD-02" "--dest" "merge-e2e-01"])))
  (testing "flags and overrides"
    (is (= {:dry-run? true :config-from "GTD-02" :out "/tmp/o" :root-dir "/r"}
           (select-keys (cli/parse-args ["--sources" "CRM-Simple,GTD-02" "--dest" "d" "--dry-run"
                                         "--config-from" "GTD-02" "--out" "/tmp/o" "--root-dir" "/r"])
                        [:dry-run? :config-from :out :root-dir])))))

(deftest invalid-commands-explain-what-to-fix
  (is (= {:error "--sources is required: a comma-separated list of graphs, first one wins conflicts"}
         (cli/parse-args ["--dest" "d"])))
  (is (= {:error "--dest is required: the name of the new graph to create"}
         (cli/parse-args ["--sources" "A"])))
  (is (= {:error "--dest must be a new graph, but \"A\" is also a source"}
         (cli/parse-args ["--sources" "A,B" "--dest" "A"])))
  (is (= {:error "--config-from must be one of the sources, got \"C\""}
         (cli/parse-args ["--sources" "A,B" "--dest" "d" "--config-from" "C"])))
  (is (= {:error "Unknown option: --force"}
         (cli/parse-args ["--sources" "A" "--dest" "d" "--force"]))))
