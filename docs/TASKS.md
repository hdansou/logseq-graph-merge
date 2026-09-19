# Tasks

Rules:
- Every feature, change or bug fix gets its tasks listed here **before** work starts.
- Code is built TDD: failing test, then pass, then refactor. Keep it DRY and KISS.
- Docs are updated in the same step as the code.

Status: `[ ]` todo · `[~]` in progress · `[x]` done

## T1 Requirements and options doc
- [x] T1.1 Research the Logseq export/import, identity rules and CLI surface
- [x] T1.2 Write `docs/requirements.md` (requirements, traps, options, decisions)

## T2 Merge workflow diagram
- [x] T2.1 Write `docs/merge-workflow.md`: the end-to-end pipeline, planner stages, page-matching and ontology decision diagrams (Mermaid)
- [x] T2.2 Publish a rendered view of the diagrams for review: https://claude.ai/code/artifact/fc366529-6a4c-49da-a996-85be42363d14
  - The page is built from the Mermaid blocks in `docs/merge-workflow.md` by `python3 scripts/build_workflow_page.py`, which writes `docs/site/merge-workflow.html` from `docs/site/merge-workflow.template.html`. Rebuild and republish whenever the diagrams change.
- [x] T2.3 Link the workflow doc from `docs/requirements.md`
- [x] T2.4 Update R6 to group-then-resolve (found while diagramming), and remove R4's class rename case

## T3 Spike: confirm the unverified behaviour (requirements §5)
Scope and safety:
- Probes run only against throwaway local graphs named `merge-spike-*`, created and removed with the `logseq` CLI.
- Existing graphs are only exported, never written. `cliworker` is synced, so nothing is imported into it.
- Record the CLI and worker revision, since the installed desktop build may differ from master.
- Probe fixtures and scripts go in `spike/`. Spike code is throwaway, so TDD does not apply; the findings are what matter.

Full results table: `docs/requirements.md` §5.

- [x] T3.0 CLI and worker are `b09316a`, the installed desktop build. It differs from master in the export/build files, but not in the behaviour probed. Fixtures are in `spike/fixtures/` (A basic, C same-title, E conflict, G page tree, H asset property ref).
- [x] T3.1 Import errors are **dropped**: exit 0 and "Imported" even when rejected (trap 6)
- [x] T3.2 **Page parents and orders are dropped** by `:graph-human` (new trap 9). Re-attaching via `:block/parent` + `:block/order` works. All built-ins are exported, and duplicate built-ins exist in `Demo-Graph` (trap 10).
- [x] T3.3 Order keys are identical across two imports
- [x] T3.4 A property-referenced asset keeps its uuid. An unreferenced one loses it but keeps its checksum.
- [x] T3.5 Duplicate class titles pass validation
- [x] T3.6 Export under 1 s; a 214 KB import took about 6 s
- [x] T3.7 Same-title pages with distinct kept uuids stay separate
- [x] T3.8 Code-block properties import correctly and render as a code block in the app (see T3.12)
- [x] T3.9 Findings recorded:
  - requirements: traps 4–6 confirmed, traps 9–11 added, R2a/R6b added, R7/R10/R11/R12/D5 updated, §5 results;
  - workflow diagrams and the published page updated.
- [x] T3.10 Checked that (title, created-at) uniquely identifies every page in 4 real graphs, so R2a can use it to recover page uuids
- [x] T3.11 Removed the spike graphs (`merge-spike-a`, `-a2`, `-c`, `-e`, `-g`, `-libtest`) with `logseq graph remove`; verified gone from `graph list` and from disk
- [x] T3.12 Checked the code block by eye: in `merge-spike-a`, page `Shared`, the CSS block renders as a code block in the desktop app (confirmed by the user, 2026-09-13).

## T4 Build the tool
The user approved the stack on 2026-09-13 (requirements §4, Option B):
- a pure planner in ClojureScript under nbb-logseq, depending on the local logseq repo's `deps/db`;
- a thin I/O shell that shells out to the `logseq` CLI.

**Graph scope for development (set by the user, 2026-09-13):**
- Only `CRM-Simple`, `Bafigo`, `Library-Test`, `Demo-Graph`, `GTD-02`, `cliworker`, `plugin-test`, `do-sync` and `test-rtc` may be exported, queried or otherwise used.
- `cliworker`, `do-sync` and `test-rtc` are synced, so they are read-only.
- Creating any destination graph needs the user's confirmation first.

Setup is copied from `logseq/deps/db`, not invented: same `@logseq/nbb-logseq` git ref, `nbb.edn` `:local/root` dependency, and `nextjournal.test-runner`.

- [x] T4.1 Scaffold: `nbb.edn`/`package.json`, test runner, and README with dev commands.
  - The smoke test was red (missing namespace), then green.
  - A negative test shows the `validate-export` gate really rejects bad input.
- [x] T4.2 Shared test fixture builders (DRY) in `test/graph_merge/fixtures.cljs`: `source`, `page`, `built-in-page`, `page-row`, `titles`. They grow only as tests need them (YAGNI).
- [x] T4.3 S1 normalize and recover identities (R2a, R7 duplicates, R11 drops) in `src/graph_merge/stage/normalize.cljs`; 7 tests, all written red first.
  - [x] drop `::kv-values` and `::property-history`; set `::graph-files` aside
  - [x] drop `$$$views`, `Recycle` and deleted pages. A probe showed deleted blocks never appear in the export, so no block filter is needed.
  - [x] union duplicate built-in pages within one source
  - [x] recover page uuids: by uuid when the export kept one, else by (title, created-at). Record source parent and order; abort on ambiguous or unmatched pages. Built-in pages get Logseq's deterministic `:builtin-block-uuid`.
  - [x] recover asset uuids by checksum, reusing `sqlite-build/update-each-block` for the recursive walk
  - [x] Real-data check (`spike/check_identity.cljs` on the `Library-Test` export plus live rows): 291 pages normalized, every non-journal page's uuid recovered, 158 source page-parent links recorded, no ambiguity
- [x] T4.4 S2 uuid collisions (R9); 6 tests, red first
  - [x] `graph-merge.uuid`: `derived-uuid` (deterministic, RFC 4122 v5 style via SHA-1), `shared-by-design?` (`0000000N-` prefixes), and `rewrite-uuids` (uuid values plus uuid text inside strings, anywhere in nested data). S8 will reuse `rewrite-uuids`.
  - [x] `graph-merge.stage.uuids`: find `:block/uuid` identities repeated across sources. The first source keeps its uuid; later sources are re-keyed with `derived-uuid(graph, uuid)` and all their own references are rewritten. Every re-key is reported.
- [x] T4.5 S3 property unification (R3) in `graph-merge.stage.ontology`, which S4 shares (DRY: group by normalized title, first member canonical); 6 tests, red first
  - [x] compatible same-title properties (case- and space-insensitive) map to the first source's ident
  - [x] a type or cardinality mismatch renames the later property to `"title (graph)"` with an ident built by Logseq's `normalize-ident-name-part`, and is reported
  - [x] closed values are unioned by value, first source's order first; matching later closed-value uuids map to the canonical ones (`:uuid-map`)
  - [x] closed values with a blank `:value` never merge (real case: `Bafigo`'s `Effort` has several)
  - [x] other schema differences keep the first source's value and are reported
  - [x] Real-data check (`spike/check_ontology.cljs`, 4 graphs): 31 definitions became 29 merged, with no renames, no untitled definitions, and 1 reported difference
- [x] T4.6 S4 class unification (R4), in the same namespace; 3 tests, red first. The property and class diffs share one `schema-diff` (DRY).
  - [x] same-title classes map to the first source's ident
  - [x] `:build/class-properties`: ordered union after mapping through the S3 property ident maps
  - [x] `:build/class-extends`: union after mapping through the class ident maps (built-in parents such as `:logseq.class/Task` pass through)
  - [x] a union that creates an extends cycle falls back to the canonical member's extends, and is reported
  - [x] other differences (such as an icon in `:build/properties`) keep the first source's value and are reported
  - [x] Real-data check (4 graphs): 21 classes became 16. `company` from 3 graphs merged with properties `[website url]`; 3 icon differences reported; no cycles.
- [x] T4.7 S5 ident rewriting (R8) in `graph-merge.stage.idents`; 3 new tests plus origin assertions in the S3/S4 tests, red first
  - [x] S3/S4 tag every merged definition with its origin `:graph-merge/graph`, because a definition's own refs (property-classes, class-page property keys) use that graph's idents
  - [x] per source, replace mapped idents everywhere in pages and blocks: property keys, `:build/tags`, and view settings such as `hidden-columns` and `sorting`. Uses `clojure.walk/postwalk-replace` with the graph's property and class maps combined. The source's own `:properties`/`:classes` are dropped, since S3/S4 replaced them.
  - [x] merged definitions are rewritten with their origin graph's maps
  - [x] strings such as query text are not rewritten; every mapped ident found inside a string is reported (R8)
- [x] T4.8 S6 assets (R10) in `graph-merge.stage.assets`; 3 tests, red first
  - [x] the first asset for a checksum is kept and listed for copying: `{:graph :uuid :type :checksum}`
  - [x] a later asset with the same checksum becomes a plain block `[[<kept uuid>]]`, keeping its children so nothing is lost; its uuid maps to the kept one (`:uuid-map`), and this is reported
  - [x] an asset block without a uuid aborts, since its file can't be located (S1 should have recovered it)
- [x] T4.8b (1 test, red first) S3/S4 amendment: every merged definition carries a kept `:block/uuid`. That is the canonical member's uuid, else the first member's that has one, else `(gen-uuid :db-ident-block-uuid ident)`, which is build's own default. Other members' uuids go into `:uuid-map`. Needed because tag/property pages are exported as `{:page {:block/uuid u}}` (seen in Library-Test), and a later graph's page must find the merged definition.
- [x] T4.9 S7 page matching in `graph-merge.stage.pages` (R4, R5, R6, R6b, R7), one TDD cycle per rule; 16 tests
  - [x] S7a journals: one page per day, blocks in source order, page properties first-wins
  - [x] S7b built-ins: one page per title, blocks appended; `$$$favorites` deduplicated by `:block/link` target
  - [x] S7c tag/property page entries (`{:block/uuid u}`): grouped by uuid through the S3/S4 uuid map
  - [x] S7d regular pages: group by normalized title plus parent path, then transitive tag-overlap clusters and the untagged rule. Covers every row of the workflow examples table, plus transitive overlap, case/space-insensitive titles and different parents. Emits one page per cluster; other members' uuids go into `:uuid-map`.
  - [x] S7e class vs page: only a top-level page with a merged class's title joins the class page; a nested one stays a page
  - [x] S7f page tree (R6b): parent = earliest member's parent through the page uuid map. Shared built-in parents such as Library stay valid. A missing parent re-roots the page and is reported. Sibling orders are sorted by (source, source order key, title) and come from `gen-n-keys` with a local max-key atom (pure). Helper keys are stripped.
  - [x] Report data: `:page-merges`, `:class-page-merges`, `:re-rooted`
- [x] T4.10 S8 uuid ref rewriting (R8) in `graph-merge.stage.refs`; 2 tests, red first
  - [x] combine the stage uuid maps and resolve chains (a→b, b→c gives a→c), with a guard against cycles
  - [x] rewrite `[[uuid]]` text and `[:block/uuid …]` values in merged pages and blocks and in merged definitions, reusing `guuid/rewrite-uuids`
- [x] T4.11 S9 Graph Merge page and report (R12) in `graph-merge.stage.review`; 4 tests, red first
  - [x] one block per source, in order, with a `config.edn` code block (clojure) and a `custom.css` code block (css); a missing file gives an empty code block
  - [x] a `Merge report` block with one child per section, each holding a clojure code block of the pretty-printed section data (readable, deterministic, readable back as EDN)
  - [x] the title becomes `Graph Merge (tool)` if a source already has a top-level `Graph Merge` page
  - [x] the page has a deterministic kept uuid
- [x] T4.12 S10 sort and emit, plus `plan` composing S1–S10 (D1, D2); 5 new tests, red first
  - [x] `graph-merge.stage.emit`: journals by day, then other pages by normalized title (uuid breaks ties); strip every `:graph-merge/*` helper key; `::export-type :graph-human`; include `::graph-files` only for `--config-from`
  - [x] `plan`: compose the stages, collect report sections (including counts for the D5 post-check), add the Graph Merge page, and return `{:export :report :asset-files}`
  - [x] integration test: two sources shaped like the real exports (closed values in both, a shared tag, the same journal day, a Library child, a cross-page `[[uuid]]` ref). The result **passes Logseq's `validate-export`**, and the same input gives byte-identical `pr-str` output (D1).
  - [x] the smoke test changed: merging no sources now yields just the Graph Merge page
  - Test-data lesson: `#uuid` accepts non-hex text such as `…acme`, but real uuids are hex and the uuid-text regex rightly ignores anything else. Fixtures must use hex.
- [x] T4.12b Real-data integration check (`spike/check_plan.cljs`): the full planner over CRM-Simple, Bafigo, Library-Test and Demo-Graph exports with live identity rows, then Logseq's `validate-export`. It found 3 defects, each fixed test-first after reproducing it in `plan_test`:
  - [x] report block counts were always 0 (`tree-seq` applied to a seq instead of each block)
  - [x] `validate-export` crashed ("Cannot read properties of null (reading 'charAt')"). S1 had added `:build/keep-uuid?` to title-less tag/property page entries, which makes sqlite.build create a new page with no name. Those entries are now left exactly as exported.
  - [x] Demo-Graph's user page `Card` landed on the built-in `#Card` tag, because import matches titles exactly. The **raw Demo-Graph export fails `validate-export` on its own, with 6 errors**, so this is an existing Logseq issue. S7e now sends a top-level page titled exactly like a built-in tag or property to that built-in's page (R4), using Logseq's `built-in-classes`/`built-in-properties` titles.
  - [x] favorites deduplication (Library favorited in two graphs) is now reported, so block counts reconcile: 1,436 source blocks = 1,435 merged + 1 deduplicated favorite
  - Result: **`validate-export` :ok** for the 4-graph merge. 492 pages became 421 (5 page merges, 2 definition-page merges, 11 skipped), 29 properties, 16 classes, 3 assets, 3 uuid re-keys. Planning takes about 230 ms; validation about 23 s.
- [x] T4.13 + T4.14 CLI and I/O shell (R2, D4, D5). Pure helpers are built test-first; the thin `logseq` process wrapper is covered by the T4.15 end-to-end run.
  - Probe finding: `graph export` on a missing graph silently creates it (trap 14). An accidental `nonexistent-merge-probe` was created and removed at once. The wrapper must never run a graph-scoped command before preflight has checked `graph list`.
  - [x] `graph-merge.io` (tests first):
    - EDN read with unknown tags as `tagged-literal`, and a printed round trip that keeps `#transit/time` values;
    - asset file path `<root>/graphs/<encoded graph>/assets/<uuid>.<type>` via Logseq's `encode-graph-dir-name`;
    - SHA-256 of a file.
  - [x] `graph-merge.cli` args (tests first): `--sources a,b,c --dest name [--dry-run] [--config-from g] [--out dir] [--root-dir dir]`, with clear errors for missing or invalid options (dest listed as a source, config-from not a source).
  - [x] `graph-merge.verify` (tests first; 8 tests across io, cli and verify; block counting shared with the planner through `graph-merge.blocks`):
    - expected block count per page uuid, taken from the export (journal uuids from the day);
    - comparison with the destination's counts, reporting missing pages and pages with fewer blocks;
    - an asset check (file exists, checksum matches) before writing.
  - [x] `graph-merge.logseq`: a thin `spawnSync` wrapper for `graph list`, `graph info`, `graph export`, `query -o edn`, `graph import`, `graph validate`. A non-zero exit throws with stderr.
  - [x] `graph-merge.main`, which runs in order:
    1. preflight: sources exist, destination absent, equal schema versions;
    2. extract into `--out`;
    3. plan, writing `merged.edn` and `report.edn`;
    4. `validate-export` plus the asset check;
    5. stop here for `--dry-run`;
    6. import, then copy assets;
    7. verify counts, then `graph validate`.
  - [x] `pnpm merge` script and README usage
  - The EDN reader must accept unknown tags: real exports contain `#transit/time` values. Read them as `tagged-literal` and check they print back unchanged for import.
  - Page identity query that worked on real data: `[:find [(pull ?p [:block/uuid :block/title :block/created-at :block/order {:block/parent [:block/uuid]}]) ...] :where [?p :block/name]]` run with `-o edn`
- [x] T4.15a First dry run (5 sources) worked end to end: preflight, extract, plan, then the validation gate **stopped before writing** with 14 errors. `merge-e2e-01` was not created. Breakdown (`spike/error_summary.cljs`):
  - 12 errors are GTD-02's own: its raw export fails alone with 17. They are 10 from `Duration` (a number property) set to `:logseq.property/empty-placeholder`, and 2 from built-in `Status` set to free-text value blocks ("Icebox", "This Week").
  - plugin-test's raw export fails alone with 5 (graph-file timestamps); the merge already fixes those by dropping graph files.
  - 1 error comes from the merge: a live `depends_on` value points at a deleted page ("Budget approval") that S1 skips.
  - The user's decisions (2026-09-13) are recorded in requirements R2b.
- [x] T4.15b S1b unimportable values, in `graph-merge.stage.values`; 6 tests plus integration fixture data, red first
  - [x] drop `:logseq.property/empty-placeholder` values of `:number` properties, and report them
  - [x] a `:build/property-value` block on a built-in property with fixed choices (`:closed-values`) becomes a child note block `"<Property title>: <value>"`, and is reported
  - [x] a property value pointing at a skipped (deleted) page is removed (for many-valued properties, only that element) and becomes a child note block `"<property title>: <page title> (deleted page)"`, and is reported. The same applies to page properties, whose notes go on the page.
  - [x] `[[uuid]]`/`#[[uuid]]` text refs to skipped pages become the page's plain title
  - [x] S1 reports skipped pages with their uuid, so S1b can resolve titles
  - [x] wired into `plan` after S1; the dry run gate passes
- [x] T4.15c The second dry run passed validation. Checking the report before writing found that blank-titled pages were being merged (7 unrelated untitled pages became one). Fixed test-first: a blank title groups only with itself. The routing of `DONE`/`Tutorial` onto definition pages was checked and is correct: user tags `done`/`tutorial` exist (R4).
- [x] T4.15 End-to-end run. The user decided on 2026-09-13:
  - sources `CRM-Simple, GTD-02, Library-Test, Demo-Graph, plugin-test`, in that order;
  - destinations are throwaway `merge-e2e-NN` graphs created with the CLI and removed after the user's review.
  - [x] dry run first, then a real run into `merge-e2e-01`: `Done` in about 2 minutes. (Found 2026-09-14: this run used mixed revisions, `b09316a` for the source exports and `f7362f0-dirty` for the destination import; trap 15, T5.9.) Import, asset copy, block-count verification and `graph validate` all passed.
  - [x] Independent verification with separate queries, not the tool's own checks:
    - the destination has its own `local-graph-uuid`;
    - Library has 21 children and there are 159 page-parent links;
    - no duplicate user property or tag titles;
    - 10 asset entities and 10 files;
    - 24 code blocks on Graph Merge;
    - the R2b notes are present;
    - journal 2025-08-02 is 1 page with 27 blocks (23 CRM-Simple, then 4 GTD-02);
    - 2025-09-12 holds 10 blocks: 8 source blocks plus 2 `Status: This Week` notes, matching the export exactly.
  - [x] the user reviewed `merge-e2e-01` in the desktop app ("looking good", 2026-09-14). It was then removed with `logseq graph remove`; verified gone from `graph list`, disk and `server list`.
- [x] T4.16 Docs: README usage, requirements R2b and blank titles, workflow S1b, page republished (version 5). Recap audit 2026-09-14 corrected R2 (schema check across sources, graph list first), R12 (actual report sections), D4/D5 (the post-import checks as built), the workflow verify step, and the `lastVerified` dates.

## T5 Open items and backlog
Tracked here so nothing lives only in the chat.

Open:
- [x] T5.1 The user reviewed `merge-e2e-01` and approved it, and it was removed (2026-09-14).
- [x] T5.2 Put `logseq-graph-merge` under git (the user approved it on 2026-09-14). No remote; it stays local.
  - [x] `.gitignore` keeps `out/`, `spike/out/`, `node_modules/` and `.nbb/` (138 MB cache) out, because the `out/` folders hold exports of personal graphs
  - [x] reviewed the 60 files to commit (source, tests, synthetic fixtures, scripts, docs) and scanned them for secrets; initial commit on `main`

- [x] T5.8 Wrote `docs/getting-started.md` (tutorial plus troubleshooting) and linked it from the README. Every command was checked on this machine on 2026-09-14:
  - a two-graph dry run (`CRM-Simple,Demo-Graph` into `merge-e2e-02`, about 4 s, no graph created);
  - the prerequisites, and what installs the CLI (`src/electron/electron/core.cljs:404`).
  - Found along the way: the CLI is now `f7362f0-dirty`, because each desktop app rewrites `~/.local/bin/logseq` on start (trap 15), so the first end-to-end run used mixed revisions. Also, a server wouldn't start for `Library-Test` ("failed to publish health"); it is recorded as a troubleshooting entry, not diagnosed further.
- [x] T5.9 Re-run the five-graph merge once per Logseq build, with every server on that build (stop running servers first), each into a new `merge-e2e-NN`. The user tests two builds: stable `Logseq.app` (`b09316a`) and upcoming `Logseq-DB.app` (`f7362f0-dirty`). The first run mixed them (trap 15). The user approved on 2026-09-14.
  - [x] T5.9a Choose the build without launching a desktop app (tests first; 3 tests):
    - `GRAPH_MERGE_LOGSEQ_APP=/Applications/<App>.app` makes the tool run `<App>/Contents/MacOS/Logseq <App>/Contents/Resources/app.asar/js/logseq-cli.js` with `ELECTRON_RUN_AS_NODE=1`, the same command the managed wrapper runs;
    - preflight prints the CLI revision (from `--version`).
  - [x] T5.9b `Library-Test` starts again with both builds (2026-09-14). The earlier "failed to publish health" was transient. Note: a CLI of one build reuses a server already running on another build, so stop servers before a per-build run.
  - [x] T5.9c Stable run (`GRAPH_MERGE_LOGSEQ_APP=/Applications/Logseq.app`; all source and destination servers confirmed on `b09316a`):
    - the dry run passed: 517 pages, 1,969 blocks, 10 assets. `plugin-test` now exports 163 pages, not 161.
    - **The real import failed:** `Failure(Conflicting upsert: -1 resolves both to 3111 and 3120)`. The tool stopped before copying assets.
    - Controlled checks on the same `merged.edn`:
      - upcoming `f7362f0-dirty` CLI into `merge-e2e-03`: **imported** (791 pages, Graph Merge present);
      - stable CLI directly into `merge-e2e-04`: **same failure, same entity ids**, so it is deterministic;
      - `b09316a`'s own `deps/db` `build-import` plus transact in memory (`git archive` into the scratchpad, nbb): **ok**, 3,798 tx items.
    - Conclusion: the defect is in the stable build's *worker* import path, beyond the shared build code, and the upcoming build no longer has it. The in-memory gate can't catch it because it runs master's `deps/db`, not the worker. Not narrowed further (time-boxed); recorded as trap 16.
    - The empty or diagnostic graphs `merge-e2e-02/03/04` were removed.
  - [x] T5.9d Upcoming run. **The upcoming build can't open `Library-Test`** (`server-start-timeout-orphan`; the worker log shows `Cannot store nil as a value at {:db/id nil, :block/tx-id …}` from `ensure-canonical-revisions!`, which is also on master). Reproduced twice; stable opens it. This corrects T5.9b, which had called the failure transient; it only seemed to work when a stable server was reused. Recorded as trap 17. So both builds were run on the **4 other sources** (`CRM-Simple,GTD-02,Demo-Graph,plugin-test`), every server confirmed on one build:
    - upcoming `f7362f0-dirty` into `merge-e2e-05`: **Done** in 31 s (279 pages, 1,420 blocks, 9 assets). Spot-checked: its own graph uuid, no duplicate property or tag titles, 9/9 assets, journal 2025-08-02 with 27 blocks, deleted-page note present.
    - stable `b09316a` into `merge-e2e-06`: **import failed** with `Conflicting upsert` (2222/2231), so trap 16 doesn't depend on `Library-Test`. The empty graph was removed.
  - [x] T5.9e Comparison: **both builds export byte-identical EDN** for all 4 sources, so `merged.edn` and `report.edn` are identical too (D1 holds across builds). The only difference is at import. Guidance: run real merges on the upcoming build (getting-started, README, traps 16–17).
  - [x] T5.9f The user reviewed `merge-e2e-05`: "looking good", with one issue, tags rendering as `#<uuid>` (see T5.12).
- [x] T5.11 Upstream reports, all verified against upstream `master` `8e15eeecdf` (2026-09-17) before filing; none of the 29 commits since the running build touch these paths. Filed with no labels (the form applies none):
  - [db-test#1212](https://github.com/logseq/db-test/issues/1212) — imported tag pages named after the ident (trap 19, `build.cljs:473`)
  - [db-test#1213](https://github.com/logseq/db-test/issues/1213) — `graph export` silently ignores top-level `--edn-options` keys (trap 18, `graph.ml:404-406`)
  - [db-test#1214](https://github.com/logseq/db-test/issues/1214) — worker exits at startup when a `:block/uuid` index entry has no entity (trap 17, `db_core.cljs:714-724`)
  - Not filed by decision: trap 16 (stable `b09316a` rejecting merged imports with `Conflicting upsert`). The upcoming build imports the same file, so it is already fixed in the code maintainers work on.
- [ ] T5.10 Idea, not started: record the CLI revision and each source server's revision in `report.edn`, so every merge shows which build produced it.

- [x] T5.12 Tags rendering as `#<uuid>` (user report, 2026-09-17). Investigated:
  - the block data is **identical** in source and merge: same title with `#[[uuid]]`, same `:block/refs`, same `:block/tags`, and the referenced uuid exists in both;
  - the only difference was the tag page's `:block/name`, which imports set from the ident (`warning-a04sq4ln`). Reproduced by importing one graph's own export, so it is upstream (trap 19). Fixed in S10 by emitting `:block/name`; `merge-e2e-08` now has `warning`/`cite`.
  - the user also sees the `#<uuid>` rendering in the source graph, so it is not caused by the merge. **After the fix the user reported the merged graph renders "much better" (2026-09-17)**, so the tag page name does drive the rendering; the source graphs still show it because their own tag pages came from the plugin/app.
- [x] T5.13 While re-running, exports came back with no timestamps: the newest build moved `:include-timestamps?` under `:graph-options` (trap 18). The tool now sends both shapes and fails loudly if an export has no timestamps. The run aborted safely before writing.
- [x] T5.14 The user reviewed `merge-e2e-08`: "much better". Both `merge-e2e-05` and `merge-e2e-08` were removed (2026-09-17); none left in `graph list`, on disk or in `server list`.

- [x] T5.15 `Library-Test` opens again on current builds. The 3 phantom `:block/uuid` index entries were deletion remnants dating back to 2025; the index rebuild was applied to the live graph on 2026-09-18 with the user's go-ahead, after a file-level backup. See [library-test-investigation.md](library-test-investigation.md) for the full record and rollback path.
  - Still open, parked by the user: adding the new evidence to db-test#1214, and reporting `test-rtc`'s SQLite-level corruption upstream (a separate bug; the graph itself was restored from a backup on 2026-09-18, but its sync is deliberately still stopped).

Known limits (by design, not started; decide before building):
- [ ] T5.3 Idents inside query text are reported (`idents-in-text`), not rewritten (R8).
- [ ] T5.4 Uuids of unreferenced blocks can change between runs (D3); uuid-v5 for every block is a possible extension.
- [ ] T5.5 `graph import` gives no error text on rejection (trap 6). Option D (calling `/v1/invoke` directly) would surface it; today the `validate-export` gate catches problems first.
- [ ] T5.6 Merging into an existing graph, or incremental re-merges, are out of scope (D6).
- [ ] T5.7 Not yet seen in real data, so not handled: favorites linking to deleted pages, and empty-placeholder values on types other than `:number`. The validation gate would stop the merge if they appear.

## Progress log
- 2026-09-13: T1 done. Requirements doc drafted from code reading of logseq @ `d2ab7726ab`.
- 2026-09-13: T2 started.
- 2026-09-13: Found while diagramming: resolving pages one at a time made the untagged-page rule depend on source order. R6 now groups pages by title and parent path, then resolves each group, with transitive tag-overlap clusters. R4's class rename case was removed because classes have no type or cardinality to conflict on. The requirements doc and decisions log are updated.
- 2026-09-13: T2 done. Published the workflow page (four diagrams, rendered from the Markdown so the page and doc can't drift). Next up: T3 spike.
- 2026-09-13: T3 done (spike against `b09316a`). Most important finding: `:graph-human` drops page parents, orders and most page uuids (218 of 293 in `Library-Test`). A plain export/import flattens the Library and namespace tree. The fix is the new R2a (identity queries, uuid recovery by title and created-at, or by checksum for assets) and R6b (re-attach parents with fresh sibling orders); both proven on fixtures. Also confirmed: `graph import` exits 0 when it rejects the EDN, and imported kv-values copy the source's `local-graph-uuid`. Option B still recommended. T4 tasks listed; waiting for a go-ahead on the stack.
- 2026-09-13: T3.12 done. The user confirmed the CSS code block renders in the desktop app, so R12's code-block approach is fully verified.
- 2026-09-13: T3.11 done (spike graphs removed). T4 started after the user approved the stack.
- 2026-09-13: T4.1–T4.3 done. The scaffold reuses `deps/db`'s nbb setup. S1 normalize was built test-first.
  - A short-lived probe graph showed deleted pages are exported with `deleted-at` while deleted blocks are absent.
  - Running S1 on real `Library-Test` data found two things the fixtures had missed, and both were fixed test-first:
    - tag/property pages exported as `{:block/uuid u}` with no title, so the uuid is now matched first;
    - `#transit/time` tagged values in the export, noted for the I/O reader.
  - The same run confirmed 158 page-parent links that a plain export would lose.
- 2026-09-13: T4.4–T4.6 done: S2 uuid re-keying, S3 property unification and S4 class unification, all test-first (24 tests, 64 assertions).
  - Real exports shaped two S3 tests: closed values `{:value :uuid}` referenced as `[:block/uuid u]`, and `Bafigo`'s blank closed values, which must never merge.
  - Real-data runs over CRM-Simple, Bafigo, Library-Test and Demo-Graph: 31 properties became 29 and 21 classes became 16, with no renames or cycles.
- 2026-09-13: T4.7–T4.12 done: S5 idents, S6 assets, S3/S4 definition uuids, S7 pages (16 tests), S8 refs, S9 review page, S10 emit, and `plan` composition with a determinism test. The pure planner is feature-complete: 60 tests, 163 assertions.
- 2026-09-13: T4.12b: first end-to-end planner run on 4 real graphs, gated by Logseq's `validate-export`. It exposed 3 defects the fixtures had missed (block counts, a title-less page crash, a built-in title collision), plus an unreported favorites dedupe. Each was reproduced in a test first, then fixed. The merged export now validates. The raw Demo-Graph export fails validation on its own (6 errors), and the merge resolves those. Next: T4.13/T4.14, the CLI and I/O shell.
- 2026-09-13: The user limited development to 9 graphs and allowed throwaway `merge-e2e-NN` destinations. T4.13/T4.14 CLI and I/O shell built (pure helpers test-first).
  - A CLI probe showed `graph export` silently creates a missing graph (trap 14); the one created by accident was removed straight away.
  - The first 5-graph dry run was stopped by the validation gate: 12 errors were GTD-02's own data and 1 came from a deleted-page reference. The user chose to keep the information as notes (R2b), and S1b was built test-first.
  - Report review found blank-titled pages being merged; fixed test-first.
  - The real run into `merge-e2e-01` passed all automatic checks and an independent query-based verification. 75 tests.
- 2026-09-14: Thread recap and docs audit: stale statements corrected (see T4.16). Open items: the user reviews and removes `merge-e2e-01`, and the repo is not under git yet (see T5).
- 2026-09-14: Added `docs/getting-started.md` (T5.8), verified by a two-graph dry run. Found trap 15: each desktop app rewrites the `logseq` CLI wrapper on start, so the CLI is now `f7362f0-dirty` and the first merge ran on mixed revisions. Added T5.9 to re-run on one revision.
- 2026-09-14: The user approved `merge-e2e-01` after review; it was removed (T5.1). The user confirmed two Logseq builds are installed on purpose (stable `b09316a`, upcoming `f7362f0-dirty`); trap 15 and T5.9 reworded, T5.10 idea added. Git setup started (T5.2).
- 2026-09-14: T5.9 done. Build selection via `GRAPH_MERGE_LOGSEQ_APP` (T5.9a, 78 tests). The per-build runs found two Logseq defects: stable `b09316a` rejects merged imports with `Conflicting upsert` (worker-side; the same file imports on upcoming, trap 16), and upcoming/master can't open `Library-Test` (trap 17). 4-source runs: upcoming merged into `merge-e2e-05` and verified; stable failed at import. Exports are byte-identical across builds. Diagnostic graphs `merge-e2e-02/03/04/06` were removed.
- 2026-09-17: User reported tags rendering as `#<uuid>` in `merge-e2e-05`. Root-caused to upstream: imported tag pages are named after the EDN key (trap 19), reproduced without merging. Fixed in S10 (emit `:block/name`). The re-run then exposed trap 18 (timestamps option moved in build `6bf8fe7-dirty`), fixed by sending both shapes plus a guard. New verified merge in `merge-e2e-08`; 81 tests. The user confirmed the rendering also happens in the source graph, so it is not merge-related.
- 2026-09-17: The user confirmed the tag fix ("much better") and both review graphs were removed. No merge-e2e-* graphs remain. Open: T5.11 (file the upstream reports?) and the T5.3-T5.7/T5.10 backlog.
- 2026-09-17: Filed the two clean upstream bugs as db-test#1212 and #1213, with the investigation included per the user's preference. Filing notes saved to memory.
- 2026-09-17: Before filing the last report, fetched `upstream` (note: `origin` is the user's fork and was 9 days stale) and confirmed all three bugs are unchanged on `master` `8e15eeecdf`. Filed db-test#1214. Root cause of trap 17 proven read-only on a copy: 3 of 1,215 `:block/uuid` AVET entries have no entity, so the startup backfill builds `{:db/id nil}`. The user's lock-file theory was checked and ruled out (no lock file, no holder). `Library-Test` stays unopenable on new builds until upstream tolerates stale entries or the file is repaired (T5.15). The probe is `spike/check_orphan_datoms.cljs`.
- 2026-09-18: Thread recap. Wrote `docs/library-test-investigation.md` as the resume note for T5.15; the next session continues there.
- 2026-09-18: T5.15 continued, all read-only or on copies.
  - Re-reproduced the failure on the installed CLI `94e1db7-dirty` against a copy, so the copy is a faithful repro environment.
  - **Origin of the phantom entries found.** They are deletion remnants, not a July regression. `git log -S` shows `fb1047d1f8` *introduced* `ensure-canonical-revisions!`, the code that trips over them — the earlier note had it backwards. The graph's own backups date the entries: 2 present by 2026-01-05, the 3rd (eid 2382, block "Observable objects for UI updates") still a live block on 2026-01-05 and a bare index entry by 2026-01-12, when its page `Mego iOS App Specification` was deleted. Of that page's 166 entities, 165 went cleanly and 1 leaked, so the defect is a rare race in the delete/flush path, not a deterministic one.
  - **Survey of the other in-scope graphs:** `CRM-Simple`, `Bafigo`, `Demo-Graph`, `GTD-02`, `cliworker`, `plugin-test`, `do-sync` are all clean. `Library-Test` is the only affected graph.
  - **`test-rtc` is corrupt at the SQLite level** (`integrity_check`: invalid page number in the `kvs` tree), on the live file as well as a `.backup` copy. Exactly one row is unreadable: `addr 0`, the datascript storage root, so the graph cannot load at all. Untouched, since synced graphs are read-only. Separate from db-test#1214.
  - **Repair proven on a copy:** replaying every `:eavt` datom into a fresh storage-backed conn rebuilds the indexes consistently. The rebuilt graph opens on the current build, and the `[e a v]` fact sets of source and rebuild are identical in both directions. Not applied to the live graph — that needs the user's go-ahead plus a backup.
  - New spike probes: `probe_phantom_context`, `probe_eid_history`, `probe_deleted_page_leak`, `probe_rebuild_diff` and `repair_rebuild_indexes` (the only one that writes, and only ever to a new file).
  - **Repair applied to the live graph** with the user's go-ahead. `logseq graph backup create` is useless here (it starts a worker, so it hits the same error), so the backup was file-level: `db.sqlite` + `db.sqlite-wal` with checksums, at `~/logseq/graph-backups/Library-Test-pre-index-rebuild-20260918T202058Z/`. After the swap the graph opens on the current build: 351 pages, 29 tags, 3 tasks, 1 asset, and 1,212 entities — the pre-repair count. The only data change is the 43 `:block/tx-id` values the startup migration was always meant to backfill.
  - Worth knowing: a *failed* open still writes to the graph's WAL, so back up `.sqlite` and `.sqlite-wal` together and do it after any failed attempt.
  - The user confirmed `Library-Test` also opens normally in the desktop app, so the repair holds for both the CLI and the app.
  - The user also confirmed `test-rtc` still fails in the app; its worker log shows `database disk image is malformed`, the SQLite-level failure, not the Library-Test one. `sqlite3 .recover` on a copy gets all 2,079 rows back but `addr 0` returns zero-length content, so the live file is unrecoverable. All 12 snapshots in its `backups/` are intact, and the newest (`2026-04-30`) is only nine minutes behind the broken file and opens cleanly on a copy. Restored on 2026-09-18 with the user's go-ahead, after backing up the broken `db.sqlite`/`-wal`/`-shm` and `client-ops-/db.sqlite` to `~/logseq/graph-backups/test-rtc-broken-20260918T204400Z/`. It now opens: 224 pages, 48 tags, 49 tasks, 3 assets, 1,151 entities, `integrity_check` ok, no phantoms. **That restore was then superseded**: sync would not work against a snapshot older than the server's state, so the user deleted the local graph and re-downloaded it. The download is structurally sound but hit a **third, unrelated defect**: it records schema-version 65.33 while migrations 65.23, 65.24 and 65.25 never ran, so the built-ins they add (`deleted-at`, `deleted-by-ref`, `asset/align`, the three `recycle/original-*`) are absent and every AVET scan of them throws — `get-render-snapshots` was returning 500. Repaired 2026-09-18 with the user's go-ahead, after they quit Logseq and with a backup at `~/logseq/graph-backups/test-rtc-pre-migration-rewind-20260918T231901Z/`: rewind `:logseq.kv/schema-version` to 65.22 with `spike/repair_rewind_schema_version.cljs`, reopen, let upstream's own migration code run. Result matches the dry run on a copy — +6 built-ins with `:db/index true`, −1 deprecated `hnsw-label-updated-at`, back to 65.33, integrity ok, 245 pages unchanged. Nothing pushed yet; the migration's writes sit in `pending-local` and go up when the app next opens the graph. **`do-sync` was repaired the same way** later the same day (backup at `~/logseq/graph-backups/do-sync-pre-migration-rewind-20260918T235035Z/`): identical profile, identical result — +6 built-ins, −1 deprecated, back to 65.33, integrity ok, 206 pages unchanged, `pending-local` 12. A sweep over all nine in-scope graphs now shows every one carrying the 65.23/65.24 built-ins and zero phantom index entries. None of the three defects beyond db-test#1214 is reported upstream.
