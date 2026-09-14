(ns graph-merge.io
  "File helpers: EDN that keeps unknown tags, Logseq asset paths and checksums."
  (:require ["crypto" :as crypto]
            ["fs" :as fs]
            ["path" :as path]
            [cljs.reader :as reader]
            [logseq.common.graph-dir :as graph-dir]))

(defn read-edn
  "Reads EDN, keeping unknown tags (real exports contain #transit/time) as tagged literals."
  [text]
  (reader/read-string {:default tagged-literal} text))

(defn write-edn [value]
  (pr-str value))

(defn read-edn-file [file]
  (read-edn (str (fs/readFileSync file))))

(defn write-edn-file [file value]
  (fs/mkdirSync (path/dirname file) #js {:recursive true})
  (fs/writeFileSync file (write-edn value)))

(defn graph-dir [root-dir graph]
  (path/join root-dir "graphs" (graph-dir/encode-graph-dir-name graph)))

(defn asset-path [root-dir graph uuid type]
  (path/join (graph-dir root-dir graph) "assets" (str uuid "." type)))

(defn sha256-file
  "Hex SHA-256 of a file, the format of :logseq.property.asset/checksum; nil when missing."
  [file]
  (when (fs/existsSync file)
    (-> (crypto/createHash "sha256")
        (.update (fs/readFileSync file))
        (.digest "hex"))))
