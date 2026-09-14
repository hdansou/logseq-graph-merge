(ns graph-merge.io-test
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [cljs.test :refer [deftest is testing]]
            [graph-merge.io :as io]))

(deftest edn-with-unknown-tags-round-trips
  (testing "real exports contain #transit/time values; they must survive read -> print -> read"
    (let [text "{:block/created-at #transit/time 1750112364210 :block/uuid #uuid \"a0000000-0000-4000-8000-000000000001\"}"
          value (io/read-edn text)]
      (is (= #uuid "a0000000-0000-4000-8000-000000000001" (:block/uuid value)))
      (is (= value (io/read-edn (io/write-edn value))))
      (is (re-find #"#transit/time 1750112364210" (io/write-edn value))))))

(deftest asset-paths-use-logseqs-graph-dir-encoding
  (let [u #uuid "a0000000-0000-4000-8000-000000000001"]
    (is (= "/root/graphs/Library-Test/assets/a0000000-0000-4000-8000-000000000001.png"
           (io/asset-path "/root" "Library-Test" u "png")))
    (testing "names are encoded the way Logseq names graph directories"
      (is (= "/root/graphs/a~2Fb/assets/a0000000-0000-4000-8000-000000000001.pdf"
             (io/asset-path "/root" "a/b" u "pdf"))))))

(deftest sha256-of-a-file-matches-logseqs-asset-checksum-format
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "graph-merge-io-"))
        file (path/join dir "abc.txt")]
    (fs/writeFileSync file "abc")
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" (io/sha256-file file)))
    (is (nil? (io/sha256-file (path/join dir "missing.txt"))) "a missing file has no checksum")))
