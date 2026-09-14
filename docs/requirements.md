---
title: Logseq DB graph merge — requirements and options
status: draft
lastVerified: 2026-09-14
verifiedScope: code reading of logseq master @ d2ab7726ab and build b09316a; live spike probes (§5) against CLI/worker b09316a; the first end-to-end merge (5 graphs into merge-e2e-01) ran on mixed revisions (b09316a source servers, f7362f0-dirty CLI and destination server, trap 15); R2/R12/D4/D5 re-checked against the implementation 2026-09-14
---

# Logseq DB graph merge: requirements and options

## Context

Goal: deterministically merge several demo **DB graphs** into one new graph.

- Journals are copied to the same date in the destination.
- Pages, including the built-in **Contents** page, are appended at the bottom of an existing page with the same identity.
- Tags (classes) and properties are replicated and reconciled **before** any content.
- Assets are copied.

This is a personal dev tool, not an upstream Logseq feature. The merge logic is diagrammed in [merge-workflow.md](merge-workflow.md), and work is tracked in [TASKS.md](TASKS.md). File and line citations below refer to the Logseq monorepo (`logseq/logseq`) at commit `d2ab7726ab`.

---

## 1. What the Logseq code already provides

| Capability | Where | Relevance to merge |
|---|---|---|
| Whole-graph build-EDN export, `:graph-human` | `deps/db/src/logseq/db/sqlite/export.cljs:944` `build-graph-export` | Produces `:properties`, `:classes`, `:pages-and-blocks`, `::kv-values`, `::graph-files`, `::property-history`. Options: `:include-timestamps?`, `:exclude-built-in-pages?`, `:exclude-files?`, `:exclude-namespaces`. |
| Import into a non-empty graph | `export.cljs:1285` `build-import` → `check-for-existing-entities` (`:1170`) | Journals match by `:block/journal-day` and pages by case-sensitive title (`ldb/get-case-page`). **Blocks are appended, never de-duplicated.** Properties and classes match **only by ident**. A type or cardinality mismatch aborts the whole import (`update-existing-properties`, `:1152`). |
| Dry-run validation | `export.cljs:1384` `validate-import-txs`, `:1423` `validate-export` (in-memory `create-conn`) | A full merge can be validated before any real graph is touched. |
| CLI export and import | `cli/lib/graph.ml:962` (export), `:1003` (import); `docs/cli/logseq-cli.md` | `logseq graph export --type edn --file F -e '{:export-type :graph-human …}'` and `logseq graph import --type edn --input F --graph G`. Import stops the server, calls `thread-api/import-edn`, then restarts it. |
| Worker thread-apis | `src/main/frontend/worker/handler/export.cljs:63,77`; HTTP `POST /v1/invoke` in `src/main/frontend/worker/db_worker_node.cljs` | `export-edn`, `import-edn`, `apply-outliner-ops`, `transact`, `q`, `pull`. One db-worker-node per graph. |
| Offline DB access (nbb) | `deps/db/src/logseq/db/common/sqlite_cli.cljs` `open-db!`; `deps/db/script/create_graph.cljs --import` | Opens `~/logseq/graphs/<graph>/db.sqlite` as a Datascript conn. |
| Asset handling | `upsert asset --path` (`cli/lib/upsert.ml` ~2418); `deps/graph-parser/script/db_import.cljs` ~58 | An asset is a `#Asset` block with `:logseq.property.asset/type`, `/size` and `/checksum` (SHA-256). Its file lives at `assets/<block-uuid>.<ext>`. |
| Journal identity | `common-uuid/gen-uuid :journal-page-uuid`; `ldb/get-journal-page-by-day` (`deps/db/src/logseq/db.cljs:565`) | Journal uuids are deterministic. The same date has the same uuid in every graph. |
| Fractional block order | `deps/db/src/logseq/db/common/order.cljs` | `gen-key` uses a global `*max-key`, so new keys sort after existing blocks. |

No graph-merge, page-merge or "import graph into graph" feature exists. The old file-graph page merge was removed in `2d5a01cbac` and `bcc478b5f7`.

---

## 2. Traps found in the code

1. **The default `graph export` wipes the target on import.** The default export type is `:graph`, a raw datom dump. Importing it runs `current-db-retract-tx`, which retracts every entity in the target first (`export.cljs:1228`, `:1277`). Always use `:graph-human`.
2. **The same tag or property name gets different idents in each graph.** `db-ident/create-db-ident-from-name` appends a random `-xNNNNNNN` suffix (`deps/db/src/logseq/db/frontend/db_ident.cljc:66`). The suffix is skipped only under nbb or when `LOGSEQ_STABLE_IDENTS` is set. Import matches by ident, so a `status` property from two graphs becomes two properties with the same title, and **no error is raised**.
3. **A `:block/uuid` collision silently overwrites.** `:block/uuid` is `:db.unique/identity`, so importing a uuid that already exists updates the existing entity. Graphs cloned from a common template may share uuids. Some uuids are identical across graphs by design:
   - `00000001-` journals
   - `00000002-` built-in or stable-ident properties and classes
   - `00000004-` built-in pages and files
   - `00000005-` journal template blocks
   - `00000006-` auto-created views
4. **`:graph-human` keeps a node's uuid only when something references it** (`remove-uuids-if-not-ref`, `export.cljs:843`, applied at `:976`). Every other node gets a `random-uuid` on import. *Confirmed live (T3.4):*
   - an asset referenced through a property keeps its uuid;
   - an unreferenced asset loses it, so its `assets/<uuid>.<ext>` file no longer matches;
   - its `:logseq.property.asset/checksum` is still exported.

   The same applies to pages: in `Library-Test`, 218 of 293 non-journal pages were exported with no uuid.
5. **Graph-level data is written blindly.** `::kv-values`, `::graph-files` and `::property-history` go straight into `:misc-tx` (`export.cljs:1325`). The kv export only excludes `schema-version` (`:813`). `config.edn` and `custom.css` behave as last-writer-wins. *Confirmed live:*
   - the kv export includes `local-graph-uuid`, `graph-created-at`, `import-type` and `imported-at`;
   - importing `Library-Test`'s export into a new graph **copied `Library-Test`'s `local-graph-uuid`**, so the two graphs now share one identity.
6. **`logseq graph import` drops import errors.** `thread-api/import-edn` returns `{:error …}` as a normal value (`handler/export.cljs:84`; `db_core.cljs:1551` at `b09316a`). Both the installed ClojureScript CLI (`src/main/logseq/cli/command/graph.cljs` at `b09316a`) and master's OCaml CLI (`graph.ml:1030`) ignore that result. *Confirmed live (T3.1):* an import rejected for a property type conflict exited 0 and printed `Imported edn from …`. Nothing was written, and `-v` showed no error.
7. **Pages have two lookup rules.**
   - Case-sensitive `ldb/get-case-page`, used by import.
   - Lowercased `ldb/get-page`, which picks the oldest eid.

   `:block/name` is not unique in the schema. A page with `:block/parent` (a Library or namespace child) is never reused by `outliner/page.cljs:352`, so a second one gets created.
8. **The desktop app does not see on-disk writes.** Writing to `db.sqlite` while the app has the graph open is invisible to the app. Offline scripts need the app and db-worker-node servers stopped.
9. **`:graph-human` drops the page tree.** No page map in the export carries `:block/parent` or `:block/order`, at `b09316a` or on master. *Confirmed live (T3.2):*
   - `Library-Test` has 158 page-parent links: 21 pages under Library, plus namespace trees such as `Chap1`/`Chap2`/`Chap3` (12 children each) and `Company` → `Microsoft`;
   - after a round trip into a new graph, Library has 3 child pages and its 12 `ii` pages all sit at the top level;
   - same-title pages are **not** merged together, just flattened.

   *Workaround proven (fixture G):* a page map with `:block/parent [:block/uuid …]` and `:block/order` imports, nests correctly (under Library and under another page) and passes `graph validate`. `build-page-tx` passes page keys through (`build.cljs:644`).
10. **Sources can contain duplicate built-in pages.** `Demo-Graph` exports four `$$$favorites` and four `$$$views` pages. All six built-ins (`Contents`, `Library`, `Quick add`, `$$$views`, `$$$favorites`, `Recycle`) appear in the export; `Library-Test`'s `$$$views` holds 140 view blocks.
11. **A property built without `:block/title` is titled with its suffixed ident name** (fixture A gave `status-nhZChsDh`). Every property and class the planner emits must carry `:block/title`.
12. **A user page titled exactly like a built-in tag or property corrupts that built-in on import.** Import matches pages by exact title (`ldb/get-case-page`), and built-in tag/property entities count as pages. *Confirmed (T4.12b):* Demo-Graph's page `Card` lands on the built-in `#Card` tag, which then gets `#Page` and fails validation ("should only have one tag for a built-in entity"). **The raw Demo-Graph `:graph-human` export fails `validate-export` on its own (6 errors)**, so this is an existing Logseq issue, not one caused by merging. R4 handles it.
13. **Tag/property page entries must stay `{:block/uuid u}`.** Adding `:build/keep-uuid? true` makes sqlite.build treat the entry as a new page and call `string/capitalize` on its missing name ("Cannot read properties of null (reading 'charAt')"). Found in T4.12b.
14. **CLI commands silently create a graph that doesn't exist.** *Confirmed live (2026-09-13):* `logseq graph export --graph <nonexistent>` exited 0 with `{:status :ok :data {:message "wrote …"}}`, and the graph then existed in `graph list`, on disk and in `server list`. It was removed straight away. The memory notes say `server start` does the same. **R2 preflight must check source existence with `graph list` before running any command against a graph name.**
15. **The `logseq` CLI belongs to whichever Logseq desktop build started last.** On every start, each desktop app rewrites `~/.local/bin/logseq` to launch itself (`install-cli-launcher!`, `src/electron/electron/core.cljs:404`; marker `logseq-cli-managed`). Servers already running keep their own revision. This machine deliberately has two builds for testing: **stable `Logseq.app` (`b09316a`)** and the **upcoming release `Logseq-DB.app` (`f7362f0-dirty`)**. Launching one switches the CLI; on 2026-09-13 at 12:07 the upcoming build took over. The first end-to-end merge (12:44) therefore ran on **mixed revisions**: source exports came from `b09316a` servers, and the import into `merge-e2e-01` ran on an `f7362f0-dirty` server. Check `logseq --version` and the `server list` revision column before a run, and keep one run on one build.

---

## 3. Requirements

### 3.1 Functional

**R1 Inputs.** An ordered list of source graph names and a destination graph name. List order is precedence ("first source wins").

**R2 Preconditions.**
- Every source exists in `logseq graph list`. This check comes before any graph-scoped command, because those commands silently create a missing graph (trap 14).
- All sources report the same `logseq.kv/schema-version` in `logseq graph info`; otherwise abort. The destination is created by the same CLI and worker.
- The destination does not exist yet. `logseq graph import` creates it.
- Source graphs are only read, never written.

**R2a Extract and identity recovery.** *(added after the T3 spike)* For each source, read-only:
- `logseq graph export --type edn -e '{:export-type :graph-human :include-timestamps? true}'`.
- A **page identity query** returning `uuid`, `title`, `created-at`, parent uuid, `:block/order` and `journal-day` for every page.
- An **asset query** returning `uuid`, `checksum` and `type` for every `#Asset` block.

Then recover the uuids the export dropped (traps 4 and 9):
- **Pages** the export kept a uuid for are matched by that uuid. This includes tag and property pages, which are exported as `{:block/uuid …}` with no title.
- **All other pages** match by (`:block/title`, `:block/created-at`). This pair was unique for every page in `Library-Test`, `Demo-Graph`, `CRM-Simple` and `Bafigo`. If a pair is ambiguous or has no match, abort and list the pages.
- **Built-in pages** take Logseq's deterministic `(common-uuid/gen-uuid :builtin-block-uuid title)`, so they line up with the destination's own built-ins.
- **Deleted pages** are exported with `:logseq.property/deleted-at` and are skipped. Deleted blocks are not exported at all.
- **Assets** match by checksum.
- **Journals** already have deterministic uuids.

**R2b Values Logseq's importer rejects.** *(added after the first dry run; decided by the user 2026-09-13)* A source can hold data that its own export can't re-import (GTD-02's raw export fails `validate-export` with 17 errors). The merge keeps the information visible instead of failing or silently dropping it:
- **Empty number values:** a `:logseq.property/empty-placeholder` value on a `:number` property is dropped (it carried no value) and reported.
- **Invalid choices:** a free-text value block on a built-in property with fixed choices (e.g. `Status: Icebox`) is removed from the property. It becomes a child note block `"Status: Icebox"` under its block and is reported.
- **References to deleted pages:** a property value pointing at a skipped deleted page becomes a child note block `"<property>: <page title> (deleted page)"`. `[[uuid]]` links in text become the plain page title. Both are reported.

**R3 Properties (ontology first).**
- Unify properties across sources by normalized title (trimmed, case-insensitive).
- Properties are compatible when `:logseq.property/type` and `:db/cardinality` are both equal.
- The first source's ident is canonical; later sources' idents are remapped to it.
- An incompatible property is created as `"<title> (<source graph>)"` with a deterministic ident. That source's values move to it, and the rename is recorded in the report.
- Closed values are unioned by value title, with the first source's order first.
- Other schema attributes (`hide?`, `public?`, `ui-position`, `default-value`, `:build/property-classes`): first source wins, and differences are reported.

**R4 Tags (classes).**
- Unify by normalized title and remap idents.
- `:build/class-properties`: ordered union.
- `:build/class-extends`: union. If the union creates a cycle, fall back to the first source's extends.
- A class in one source and a plain page with the same title in another: the class wins, and the page's blocks are appended to it. This mirrors `outliner/page.cljs:358`, where creating a class converts an existing page. It applies only to top-level pages; a Library or namespace child keeps its title.
- A top-level page titled **exactly** like a built-in tag or property (e.g. `Card`, `Status`) likewise joins that built-in's page (trap 12). Built-in titles come from Logseq's `db-class/built-in-classes` and `db-property/built-in-properties`. Merged user classes match by normalized title.
- Classes have no type or cardinality, so same-titled classes always unify; there is no rename case.

**R5 Journals.**
- Match key is `:build/journal` (the day integer).
- The merged journal contains source 1's blocks, then source 2's, and so on.
- Journal page properties: first source wins.

**R6 Pages.**
- **Group first, then resolve.** All pages from all sources are grouped by normalized title **and** parent path (Library or namespace chain). Each group is then resolved as a whole, so the outcome does not depend on which source a tagged or untagged page came from. Source order only decides precedence: which uuid, title spelling and page properties win.
  - **Overlapping tag sets merge.** Tags are unioned and blocks appended in source order. Example: `Apple #Fruit` + `Apple #Fruit #Food` → `Apple #Fruit #Food`. Overlap is transitive: if A overlaps B and B overlaps C, all three merge.
  - **Disjoint tag sets stay separate.** Example: `Apple #Company` and `Apple #Fruit` both exist in the destination, which Logseq allows (`deps/outliner/src/logseq/outliner/validate.cljs:40`).
  - **Untagged pages join the tagged cluster only if exactly one exists.** If there are zero or two or more tagged clusters, the untagged pages form their own untagged page.
  - Each resulting cluster takes its uuid and title from its earliest member.
  - **A blank title never matches.** Untitled pages exist in real graphs, and grouping them merged 7 unrelated pages in the first 5-graph dry run. A page with a blank title groups only with itself.
  - See the decision diagram and examples in [merge-workflow.md §4](merge-workflow.md#4-page-matching-s7).
- Tag comparison uses the unified class idents from R4.
- Page properties: first source wins, and conflicts are reported.
- *Implementation note:* import's own page matching is title-only (`add-uuid-to-page-if-exists`, `export.cljs:1132`). Every emitted page carries an explicit `:block/uuid` with `:build/keep-uuid? true`. *Confirmed (T3.7):* two `Apple` pages with distinct kept uuids and disjoint tags import as two pages.

**R6b Page tree.** *(added after the T3 spike)* The export drops page parents (trap 9), so the planner rebuilds them from the R2a page identity query.
- Each emitted page map gets `:block/parent [:block/uuid <merged parent uuid>]`. The parent path used for R6 grouping comes from this recovered tree.
- Sibling pages under one parent get fresh order keys from `logseq.db.common.order/gen-n-keys`: sorted by source order, then by their original key. Keys from different sources can repeat (for example both `a0`), so they are never reused.
- A parent that doesn't survive (skipped or deleted) re-roots its children at the top level, and this is reported.

**R6a No provenance marker.** Appended blocks are inserted unchanged: no "From <graph>" heading, no source property. Provenance is recorded only in the merge report (R12).

**R7 Built-in and hidden pages.**
- **Contents:** append, as in R6.
- **Library:** merge the child-page tree using R6 and R6b.
- **`$$$favorites`:** union by linked target (`:block/link`), de-duplicated.
- **Duplicate built-in pages inside one source** (trap 10) are unioned into one before any cross-source merge.
- **`$$$views`, `Recycle`, and any node with `:logseq.property/deleted-at`:** skipped.

**R8 References.** Rewrite every source→destination uuid and ident mapping in:
- `[[uuid]]` and `#[[uuid]]` inside block titles;
- `[:block/uuid …]` property values;
- `:build/properties` keys and `:build/tags`;
- ident-bearing view settings: `:logseq.property.table/{hidden,ordered,pinned,sized}-columns`, `sorting`, `filters`, `:logseq.property.view/group-by-property`.

Query text (`:logseq.property/query`, advanced queries) is not rewritten. It is only flagged in the report. `:block/refs` is left for the worker pipeline to rebuild (`src/main/frontend/worker/pipeline.cljs:45`).

**R9 UUID collisions.**
- Detect uuid collisions across sources, excluding the by-design shared prefixes (trap 3).
- The first occurrence keeps its uuid.
- Later occurrences get a new uuid-v5 of (`<source graph>`, `<old uuid>`), and references inside that source are rewritten.

**R10 Assets.**
- Put back each asset block's source uuid, recovered by checksum through the R2a asset query, and force-keep it (`:build/keep-uuid? true`) in the merged EDN. This works around trap 4. The T3.4 export showed the checksum is always present even when the uuid is dropped.
- De-duplicate by checksum: first wins, and later references are rewritten to the first uuid.
- Copy `<src>/assets/<uuid>.<ext>` to `<dst>/assets/<uuid>.<ext>`, then check the SHA-256 against `:logseq.property.asset/checksum`.

**R11 Graph-level data.**
- Drop `::kv-values`. This is **mandatory**, not cosmetic: importing them copies the source's `local-graph-uuid` into the destination (trap 5, confirmed).
- Every property and class the planner emits carries `:block/title` (trap 11).
- Drop `::property-history`, so the merged graph starts with no property history.
- Keep the fresh destination's default `::graph-files`.
- `--config-from <graph>` copies that source's `logseq/config.edn` and `logseq/custom.css` verbatim instead.

**R12 "Graph Merge" review page.** The tool creates a page titled `Graph Merge` containing:
- One parent block per source graph, in source order, titled with the graph name. Under each:
  - a code block with that source's `config.edn` (`:logseq.property.node/display-type :code`, `:logseq.property.code/lang "clojure"`; see `deps/db/src/logseq/db/frontend/property.cljs:138,148`);
  - a code block with that source's `custom.css` (lang `"css"`);
  - a missing or empty file still gets its code block, with an empty body.
  - *Confirmed (T3.8):* a build-EDN block with these two properties imports with `:code` and `"css"`, and renders as a CSS code block in the desktop app.
- A `Merge report` block with one child per section. Each child holds the section's data as a pretty-printed Clojure code block. The sections, as built:
  - `sources` (in precedence order) and `counts` (pages and blocks per source; merged pages, blocks, properties, classes and assets);
  - `skipped` (hidden built-ins, deleted pages) and `value-fixes` (R2b);
  - `uuid-rekeys` (R9), `property-renames` (R3), `schema-differences` (R3/R4), `extends-cycles` (R4);
  - `idents-in-text` (R8, idents inside query strings that are not rewritten);
  - `asset-dedupes` (R10), `page-merges` (R6), `definition-page-merges` (R4), `favorite-dedupes` (R7), `re-rooted` (R6b).

The same report is written to disk as EDN next to the merged EDN file. If a source already has a `Graph Merge` page, the tool's page is named `Graph Merge (tool)` instead, and the report says so.

### 3.2 Determinism and safety

**D1 Same inputs, same output.** The same sources, order and flags produce:
- a byte-identical merged EDN file, which is kept on disk for inspection;
- the same logical graph: titles, tree, block order, properties, tags, references and assets.

**D2 Stable ordering and timestamps.**
- Export with `:include-timestamps? true`.
- Sort pages by journal day or normalized title; blocks keep their source order.
- New idents are derived from title plus a source slug, never random.

**D3 Uuids are not guaranteed stable.** These keep stable uuids:
- referenced blocks;
- assets;
- journals;
- same-title pages kept separate (R6).

Uuids of unreferenced blocks may change between runs, so tests compare content, not uuids. Deriving every uuid with uuid-v5 is a possible later extension.

**D4 Dry run.** `--dry-run` runs everything up to the in-memory `validate-export` and the source asset check. It writes `out/<dest>/merged.edn` and `report.edn` and prints a one-line summary per step, without writing any graph or asset.

**D5 Fail closed.** Abort before writing on:
- a missing source, an existing destination, or differing schema versions (R2);
- any `validate-export` error on the merged export;
- a missing source asset file or a checksum mismatch.

After the import and asset copy, the tool checks the destination (`graph-merge.verify`, results in `out/<dest>/verify.edn`):
- **Block counts per page:** one query counts non-property-value blocks per page uuid. Every page in the export must exist and hold at least as many blocks as the export gives it. Extra blocks are allowed, since a fresh graph may seed built-in pages. Journal uuids are derived from the day.
- **Copied assets:** each destination asset file must match its checksum.
- **Final check:** `logseq graph validate --graph <dest>`.

These checks are **required**: `graph import` exits 0 even when nothing was imported (trap 6, confirmed). They allow extra blocks by design, so the first end-to-end run (T4.15) was also checked independently with separate queries.

Because a failed import gives no error text, the in-memory `validate-export` in D4 is the place to catch problems. If the post-check fails anyway, the tool reports the count mismatch and says the import was rejected without a reason. Getting the real error needs Option D (calling `/v1/invoke` directly).

**D6 Re-runs.** To re-run, delete the destination graph and merge again. Incremental or repeated merges into the same graph are out of scope.

---

## 4. Options to evaluate

| | A. CLI-only orchestration | **B. EDN pipeline via CLI** *(recommended)* | C. Offline nbb in-process | D. Direct worker HTTP driver | E. Raw datom merge |
|---|---|---|---|---|---|
| How it works | `logseq query`/`list` on sources, then `upsert tag/property/page/block --blocks-file` and `upsert asset` on the destination | `graph export --type edn` (`:graph-human`) per source → pure merge planner (nbb, requires `logseq.db.sqlite.export`) → in-memory `validate-export` → `graph create` + `graph import --type edn` → copy assets → post-check + `graph validate` | nbb script: `sqlite-cli/open-db!` on sources and destination → `build-export` → planner → `build-import` → `ldb/transact!` | Same as B, but calls `/v1/invoke` `thread-api/export-edn` and `import-edn` with transit instead of shelling out to the CLI | Export `:graph` datoms, remap eids, uuids and idents, write |
| Reuses tested import and validation | Partly, per command | Fully | Fully | Fully | No |
| Determinism | Poor: the CLI mints uuids, and block-to-block refs need a second pass | High: one merged EDN file | High; nbb also gives stable idents | High | High but fragile |
| Dry run without touching a graph | No | Yes (in-memory) | Yes | Yes | Hard |
| Lock and app safety | Safe | Safe (the CLI manages servers) | **Unsafe** unless the app and servers are stopped; search index not rebuilt; must reset `db-order/*max-key` | Safe | Unsafe |
| Import errors visible | Per command | Only through the post-check; no error text (trap 6, confirmed) | Direct | Direct | Direct |
| Asset uuids | `upsert asset` creates new uuids, so refs need rewriting | Needs R10 force-keep | Direct DB access, easiest | Needs R10 | Direct |
| Effort | Medium, and slow to run | Medium | Low to medium | Medium (transit client) | High |
| Verdict | Tiny graphs only | **Recommended** | Good for prototyping the planner, not for the final write | Fallback if trap 6 is confirmed and blocks B | Rejected |

### Recommendation

**Option B**, with the merge planner written as pure ClojureScript functions under nbb-logseq that depend on the Logseq repo's `deps/db`.

- Sources are read through the supported CLI.
- The whole merge happens in data, producing one merged `:graph-human` map. That map can be inspected, diffed, and tested against in-memory conns.
- The only write is one CLI import into a fresh graph, plus copying asset files.

Because the destination starts empty, `check-for-existing-entities` only ever matches built-ins such as Contents and Library. The merge rules (R3–R12) therefore live entirely in the planner, and that is what makes the result deterministic. If CLI export turns out to be too slow, Option C can reuse the same planner functions for reading.

---

## 5. Spike results (T3, 2026-09-13)

**Setup:**
- Run against the installed `logseq` CLI and db-worker-node at `b09316a` (desktop build, 2026-07-13).
- Relevant source was also read on master `d2ab7726ab`. The import, error-handling and page-export behaviour probed here is the same at both revisions.
- Fixtures and probe scripts are in `spike/`.
- Probes wrote only to throwaway `merge-spike-*` graphs; existing graphs were only exported.

| # | Question | Result | Design impact |
|---|---|---|---|
| 1 | Does `graph import` report `{:error}`? | **No.** A rejected import exits 0 and prints "Imported"; `-v` shows nothing (trap 6). | D5 post-check is required |
| 2 | How are page trees and built-ins exported? | **Page parents and orders are dropped** (trap 9). All six built-ins are exported; a source can hold duplicate built-ins (trap 10). Re-attaching the tree via `:block/parent` + `:block/order` on page maps works and validates. | New R2a page identity query, new R6b, R7 union of duplicates |
| 3 | Are order keys reproducible? | **Yes.** The same EDN in two new graphs gave identical keys on journal and page blocks. | D1 holds |
| 4 | Does a property-referenced asset keep its uuid? | **Yes.** An unreferenced asset loses its uuid but keeps its checksum. | R10 recovers the uuid by checksum |
| 5 | Does validation flag duplicate class titles? | **No.** Two `Fruit` classes pass `graph validate`. | Title unification (R3/R4) is the planner's job |
| 6 | Export and import speed | Export under 1 s (125–214 KB EDN). A 214 KB / 689-block import took about 6 s, including server restart, and validated clean. | Option B is fast enough; C isn't needed for speed |
| 7 | Do same-title pages with distinct kept uuids stay apart? | **Yes.** Without uuids, a fresh import also doesn't merge same-title pages; it only flattens them. | Emit an explicit uuid on every page |
| 8 | Do code-block properties import and render? | **Yes.** They are stored as `:code` and `"css"`, and render as a code block in the desktop app (checked by the user). | R12 holds |

Also confirmed live:
- trap 2: the worker minted `:user.class/Fruit-Vqq8uhp6`;
- trap 5: `local-graph-uuid` is copied on import;
- trap 11: a property without a title takes its ident name;
- `[[uuid]]` content refs are rebuilt into `:block/refs` on import, as R8 assumes.

**Recommendation unchanged: Option B.** Its Extract phase now also runs the two R2a queries.

---

## 6. Decisions log (2026-09-13)

| Topic | Decision |
|---|---|
| Destination | A fresh empty graph. To re-run, delete it and merge again. |
| Incompatible property or tag schema | First source wins; later ones are renamed `"<title> (<graph>)"`. |
| Same page title, different tags | Group by title and parent path, then resolve each group. Overlapping tag sets merge (transitively), disjoint ones stay separate. Untagged pages join the tagged cluster only if exactly one exists. |
| Provenance on appended blocks | None; recorded only in the report. |
| Property history | Dropped. |
| `config.edn` / `custom.css` | Destination defaults, optional `--config-from <graph>`, plus review code blocks for each source on the `Graph Merge` page. |
| Merge report | On the `Graph Merge` page and as an EDN file. |
| Determinism | Same content (titles, tree, order, properties, tags, refs, assets). Uuids of unreferenced blocks may vary. |
| Home | Personal dev tool in `~/Projects/src/github.com/logseq-graph-merge`. |
| Recommended approach | Option B, the EDN pipeline via the CLI. |
| Development graph scope | Only CRM-Simple, Bafigo, Library-Test, Demo-Graph, GTD-02, cliworker, plugin-test, do-sync and test-rtc. Synced graphs are read-only. Destinations are throwaway `merge-e2e-NN` graphs. |
| First end-to-end sources | CRM-Simple, GTD-02, Library-Test, Demo-Graph, plugin-test (in that order) |
| Values the importer rejects | Empty number values are dropped; invalid built-in choices become a `"Property: value"` child note (R2b) |
| References to deleted pages | A child note `"<property>: <title> (deleted page)"`; text links become the plain title (R2b) |
