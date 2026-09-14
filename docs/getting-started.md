---
title: Getting started with logseq-graph-merge
status: current
lastVerified: 2026-09-14
verifiedScope: every command and sample output below was run on this machine on 2026-09-14 (macOS, Node 22.20, pnpm 10.33, babashka 1.12.218, OpenJDK 26, logseq CLI f7362f0-dirty) except where marked
---

# Getting started

This tutorial takes you from a fresh checkout to a verified merge of two Logseq DB graphs. A dry run takes a few seconds; a real merge of five graphs took about two minutes.

You will:
1. install and test the tool;
2. check the Logseq CLI and your graphs;
3. dry-run a merge and read its report;
4. run the real merge into a new graph;
5. review it in Logseq, then clean up.

To learn *how* the merge decides things, read [merge-workflow.md](merge-workflow.md). For *why*, read [requirements.md](requirements.md).

## 1. Prerequisites

| Tool | Why | Check |
|---|---|---|
| Logseq desktop app (DB version) | Provides the `logseq` CLI; each app writes `~/.local/bin/logseq` when it starts | `logseq --version` |
| Node.js 22 and pnpm 10 | Run nbb-logseq and the tests | `node --version`, `pnpm --version` |
| babashka (`bb`) | nbb-logseq calls `bb` to download the ClojureScript dependencies in `nbb.edn` the first time | `bb --version` |
| Java | Needed by babashka's dependency resolution *(not verified: this machine already had Java)* | `java -version` |
| git | Some dependencies are git libraries | `git --version` |
| The logseq source repo | `nbb.edn` depends on `../logseq/deps/db` to reuse Logseq's own export/import and validation code | see layout below |

The tool expects this layout:

```
~/Projects/src/github.com/
├── logseq/               # the Logseq monorepo (only deps/db is used)
└── logseq-graph-merge/   # this tool
```

## 2. Install and run the tests

```sh
cd logseq-graph-merge
pnpm install
pnpm test
```

The first `pnpm test` prints `Downloading dependencies...` and `Extracting dependencies...` once. It ends with:

```
Ran 75 tests containing 197 assertions.
0 failures, 0 errors.
```

Some tests check that Logseq's validation rejects bad data, so error logs above the summary are expected. Only the last two lines matter.

## 3. Check the CLI and your graphs

```sh
logseq --version        # e.g. "Revision: f7362f0-dirty"
logseq graph list       # the names you can merge
logseq server list      # running servers and their "revision" column
```

**Know which Logseq build you are using.** The CLI belongs to whichever Logseq desktop app started last. With a stable and an upcoming build installed side by side, launching either one switches the CLI. Servers that are already running keep the revision they were started with. The source graphs must report the same schema version (the tool checks), but mixing builds can still change export behaviour. If `server list` shows different revisions, stop those servers (`logseq server stop --graph <name>`) so they restart on the current build.

**Use exact graph names.** Logseq CLI commands silently *create* a graph that doesn't exist. The merge tool checks `graph list` before touching anything, but be careful when running `logseq` commands by hand.

## 4. Dry-run a merge

A dry run exports and plans, then validates the result with Logseq's own import validation. It writes files but no graph.

```sh
pnpm -s merge --sources "CRM-Simple,Demo-Graph" --dest merge-e2e-02 --dry-run
```

- `--sources` is ordered: **the first graph wins** when properties, tags or page properties conflict.
- `--dest` must be a graph that doesn't exist yet.

Output from the verified run:

```
• Preflight ok: 2 sources, schema {:major 65, :minor 33}
• Extracted CRM-Simple: 57 pages, 0 assets
• Extracted Demo-Graph: 49 pages, 0 assets
• Planned: {:pages 86, :blocks 623, :properties 15, :classes 8, :assets 0} -> out/merge-e2e-02/merged.edn, report.edn
• Validated merged export and 0 asset files
• Dry run: no graph was written
```

The working directory `out/<dest>/` now holds:

| File | What it is |
|---|---|
| `sources/<graph>.edn` | Each source's export |
| `merged.edn` | The exact data that would be imported |
| `report.edn` | Every decision the merge made |

`out/` contains your notes, and `.gitignore` excludes it. Keep it local.

## 5. Read the report

`report.edn` is plain EDN. These sections are the ones to check before a real run:

| Section | Look for |
|---|---|
| `counts` | Pages and blocks per source, and in the merge |
| `page-merges` | Pages that became one (title, members). Check that they really are the same page. |
| `definition-page-merges` | Pages that joined a tag or property page with the same title (e.g. a page `Card` joining the built-in `#Card` tag) |
| `property-renames` | Same-named properties with incompatible types; the later one is renamed `"title (graph)"` |
| `value-fixes` | Values Logseq rejects, changed as described in requirements R2b: empty number values dropped, invalid choices and links to deleted pages kept as note blocks |
| `skipped` | Hidden built-in pages and deleted pages that are not copied |
| `uuid-rekeys` | Blocks that shared an id across graphs and got a new one |
| `re-rooted` | Pages whose parent page no longer exists |

The real merge also shows this report on a `Graph Merge` page in the new graph.

## 6. Run the real merge

When the dry run looks right, run the same command without `--dry-run`:

```sh
pnpm -s merge --sources "CRM-Simple,GTD-02,Library-Test,Demo-Graph,plugin-test" --dest merge-e2e-01
```

It imports into the new graph, copies asset files and verifies the result. The last lines of the verified five-graph run (2026-09-13) were:

```
• Validated merged export and 10 asset files
• Imported into merge-e2e-01 and copied 10 asset files
• Verified merge-e2e-01: every page has its blocks, assets match, graph validate passed
• Done: merge-e2e-01
```

Verification writes `out/<dest>/verify.edn`. It lists any page missing blocks and any asset file that doesn't match.

## 7. Review in Logseq

Open the new graph in the desktop app and check:
- the **Graph Merge** page: each source's `config.edn` and `custom.css`, then the merge report;
- a journal date that exists in several sources: blocks from the first source come first;
- a page listed in `page-merges`;
- the **Library** page: child pages are back in their tree.

The new graph keeps Logseq's default settings. To use one source's `config.edn` and `custom.css` instead, add `--config-from <graph>`.

## 8. Clean up

When you're done reviewing, remove the destination from step 6:

```sh
logseq graph remove --graph merge-e2e-01
```

A dry run creates no graph, so it leaves nothing to remove. Delete `out/<dest>/` yourself when you no longer need the exports. To merge again, remove the destination (or pick a new name) and re-run. Merging into an existing graph is not supported.

## Troubleshooting

| Message | What to do |
|---|---|
| `Source graphs not found: [...]` | Fix the name to match `logseq graph list` exactly. Nothing was touched. |
| `Destination graph "…" already exists` | Choose a new name, or remove that graph first. |
| `Sources have different schema versions` | Open each source once in the same Logseq version, then re-run. |
| `… failed: … server-start-failed … db-worker-node failed to publish health` | The CLI couldn't start a server for that graph. Seen on 2026-09-14 for a graph with no running server, whose database had been written minutes before. Check `logseq server list` and whether a desktop app has the graph open, then retry. |
| `Merged export is invalid, nothing was written: … N validation error(s)` | Some source data can't be imported. First check whether a source is the cause: its own export may already fail (GTD-02 and Demo-Graph do). The spike probe `pnpm exec nbb-logseq -cp src spike/error_summary.cljs out/<dest>/merged.edn` groups the errors by kind, with an example of each. |
| `Asset files are missing or changed` | An asset file in a source graph's `assets/` folder is gone or differs from its recorded checksum. Nothing was written. |
| `Destination doesn't match the merge … see out/<dest>/verify.edn` | The import lost data: `graph import` can fail without an error. Remove the destination, check the dry run, and re-run. |

## Where next
- [requirements.md](requirements.md): the rules, the Logseq traps behind them, and the decisions log
- [merge-workflow.md](merge-workflow.md): diagrams of every phase and stage
- [TASKS.md](TASKS.md): what's done, open items and known limits
