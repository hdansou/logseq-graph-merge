# logseq-graph-merge

A personal dev tool that merges several Logseq DB graphs into one new graph:
- journals go onto the same date;
- matching pages are appended;
- tags and properties are reconciled first;
- assets are copied.

The same inputs always produce the same merge.

Status: working end to end. First verified merge (2026-09-13): 5 graphs into `merge-e2e-01`, with 516 pages, 1,969 blocks and 10 assets. See [docs/TASKS.md](docs/TASKS.md).

## Usage
The desktop app can stay open. The tool works through the `logseq` CLI, only reads the source graphs, and writes only to the new destination graph.

```sh
# Plan and validate only. Writes out/<dest>/merged.edn and report.edn and touches no graph.
pnpm -s merge --sources "CRM-Simple,GTD-02,Library-Test,Demo-Graph,plugin-test" --dest merge-e2e-01 --dry-run

# Merge into a new graph: import, copy assets, then verify block counts and run graph validate.
pnpm -s merge --sources "CRM-Simple,GTD-02,Library-Test,Demo-Graph,plugin-test" --dest merge-e2e-01
```

| Option | Meaning |
|---|---|
| `--sources` | Graphs to merge. The first one listed wins conflicts. |
| `--dest` | Name of the new graph. It must not exist yet. |
| `--dry-run` | Stop after validation; no graph is written. |
| `--config-from <graph>` | Copy that source's `logseq/config.edn` and `custom.css`. By default the new graph keeps its own, and every source's files are shown on the `Graph Merge` page. |
| `--out <dir>` | Working directory. Default `out/<dest>`. |
| `--root-dir <dir>` | logseq CLI root. Default `~/logseq`. |
| `GRAPH_MERGE_LOGSEQ_APP=/Applications/<App>.app` (environment variable) | Run that desktop build's CLI instead of the `~/.local/bin/logseq` wrapper. Use the upcoming build for real merges (see [getting started](docs/getting-started.md)). |

What you get in the new graph:
- journals merged by date;
- matching pages merged (blocks appended in source order), with the Library/namespace tree restored;
- tags and properties unified by name;
- assets copied and checksum-verified;
- a `Graph Merge` page with each source's config and the full merge report.

The same report is written to `out/<dest>/report.edn`. The tool stops before writing if Logseq's own validation rejects the merged data, and fails afterwards if the destination is missing any expected block.

## Docs
- **[docs/getting-started.md](docs/getting-started.md): start here.** Prerequisites, install, a dry run, reading the report, a real merge, review and cleanup, plus troubleshooting.
- [docs/requirements.md](docs/requirements.md): requirements, traps found in Logseq, options, spike results and the decisions log
- [docs/merge-workflow.md](docs/merge-workflow.md): workflow diagrams (source of truth for the [rendered page](https://claude.ai/code/artifact/fc366529-6a4c-49da-a996-85be42363d14))
- [docs/TASKS.md](docs/TASKS.md): task tracker and progress log

## Layout
| Path | What |
|---|---|
| `src/graph_merge/main.cljs` | The merge command: preflight, extract, plan, validate, write, verify |
| `src/graph_merge/plan.cljs` | The pure planner entry point: sources in, merged export and report out |
| `src/graph_merge/stage/` | One namespace per planner stage (normalize, values, uuids, ontology, idents, assets, pages, refs, review, emit) |
| `src/graph_merge/{cli,io,logseq,verify}.cljs` | Arguments, files, the `logseq` CLI wrapper, pre- and post-write checks |
| `test/` | Tests, written first (TDD) |
| `spike/` | Throwaway probes and fixtures from the T3 spike |
| `scripts/build_workflow_page.py` | Rebuilds the workflow page from the Mermaid in `docs/merge-workflow.md` |

## Development
Requirements:
- Node 22 and pnpm 10
- the logseq repo checked out next to this one (`../logseq`), because `nbb.edn` depends on `../logseq/deps/db` and reuses Logseq's own export/import and validation code

```sh
pnpm install   # installs @logseq/nbb-logseq (same git ref as logseq/deps/db)
pnpm test      # runs every *-test namespace under test/
```

`validate-export` logs rejected maps to the console, so tests that check rejection print error noise. That noise is expected; the pass/fail summary at the end is what counts.

To rebuild the workflow page after editing the diagrams:

```sh
python3 scripts/build_workflow_page.py
```
