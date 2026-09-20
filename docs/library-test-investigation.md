---
title: Library-Test — unopenable on current Logseq builds
status: Library-Test resolved 2026-09-18; test-rtc repaired too, via a different fix; three upstream defects still open
lastVerified: 2026-09-18
verifiedScope: reproduced live on CLI/worker 94e1db7-dirty; origin of the phantom entries traced through the graph's own backups; index-rebuild repair proven on a copy and then applied to the live Library-Test, which now opens; two further defects found in RTC graphs (test-rtc's SQLite corruption, and skipped migrations in RTC downloads) — the second repaired on test-rtc; only trap 17 is filed, as db-test#1214
---

# Library-Test: unopenable on current builds

> **Resolved for this graph on 2026-09-18.** The index rebuild below was applied to the live `Library-Test`, which now opens on the current build. The upstream defect that created the stale entries is unfixed and unreported beyond db-test#1214. Everything below is kept as the record of how it was found and what was done.

Resume note. The merge tool is finished and unrelated to this; this file is the state of the Logseq-side investigation (task T5.15).

## Symptom

`Library-Test` cannot be opened by current builds, from the app or the CLI:

```
Error (server-start-failed): Worker exited before becoming ready; startup cleanup failed:
Worker close failed: Cannot store nil as a value at {:db/id nil, :block/tx-id 536885855}
```

- Stable `Logseq.app` (`b09316a`) **opens it normally**.
- Every newer build fails: `f7362f0-dirty` (reported only `server-start-timeout-orphan`), `6bf8fe7-dirty`, and the currently installed CLI `94e1db7-dirty`, which was re-checked on 2026-09-18 against a copy and gives the message above.
- The real error is always in `~/logseq/graphs/Library-Test/db-worker-node-<date>.log`, after `{:db-worker/on-become-master-start …}`.

## Root cause (confirmed)

`ensure-canonical-revisions!` (`src/main/frontend/worker/db_core.cljs:714-724`, unchanged on upstream master `8e15eeecdf`) walks the `:block/uuid` AVET index at startup and writes `:block/tx-id` to every entity:

```clojure
{:db/id (:db/id (d/entity db (:e datom))) :block/tx-id tx-id}
```

`Library-Test` has **3 index entries with no entity behind them**, so `:db/id` is nil and the transaction throws, killing the worker.

|                                                                | Count |
| -------------------------------------------------------------- | ----- |
| `:block/uuid` datoms in the AVET index                         | 1,215 |
| Entities that exist                                            | 1,212 |
| Index entries whose `d/entity` is nil                          | 3     |
| Entities legitimately missing `:block/tx-id` (backfilled fine) | 46    |

The three phantom entries, each with no `:eavt` rows, no `:aevt` rows and **no incoming refs from anywhere in the graph**:

| eid  | uuid                                   |
| ---- | -------------------------------------- |
| 2120 | `e7a8f0a0-8e11-4aea-b36d-8bbdae54b125` |
| 2107 | `00de0000-0000-0000-0000-00000000de00` |
| 2382 | `d5eccfd9-b5a6-4d1e-b17e-423d7a1e66ce` |

**Ruled out:** a stale lock. `db-worker.lock` does not exist, no server is running for the graph, and no process holds it.

## Where the phantom entries came from (answered 2026-09-18)

They are **deletion remnants**, and they long predate the code that trips over them.

`fb1047d1f8` ("refactor: data fetch && reactivity", 2026-07-21) **introduced `ensure-canonical-revisions!`** — `git log -S` on `db_core.cljs` shows that commit and no other. It did not create the phantom entries; it is only the first build that reads the stale index. That matches the build split: `b09316a` predates it and opens the graph.

The graph's own backups (`~/logseq/graphs/Library-Test/backups/`, Jan–May 2026, plus `backup/Library-Test-20260522T125344Z`) date the entries:

| Snapshot   | uuid index | phantoms       |
| ---------- | ---------- | -------------- |
| 2026-01-05 | 1,202      | 2 (2107, 2120) |
| 2026-01-12 | 1,047      | 3              |
| 2026-02-24 | 1,048      | 3              |
| 2026-04-20 | 1,052      | 3              |
| 2026-04-28 | 1,065      | 3              |
| 2026-05-05 | 1,128      | 3              |
| 2026-05-22 | 1,201      | 3              |
| live       | 1,215      | 3              |

Entity **2382 was caught in the act**. In the 2026-01-05 snapshot it is a normal block:

```clojure
{:block/title "<a block title, redacted>"
 :block/page 2293 :block/parent 2293
 :block/created-at 1765747884433   ; 2025-12-14
 :block/tx-id 536884370 …}
```

By 2026-01-12 all of its `:eavt` datoms are gone but its `:block/uuid` AVET entry remains. Its page, 2293 (title redacted), was deleted in the same window — and that page's own uuid entry was removed correctly. Of the page's **166 entities, 165 were deleted cleanly and 1 leaked an index entry**:

```clojure
{:members 166 :counts {:gone-cleanly 165 :stale-index-entry 1} :stale [2382]}
```

So the defect is in deletion: the EAVT datoms are retracted and persisted, but occasionally the `:block/uuid` AVET entry is not, leaving the two persisted index trees disagreeing. One entity in 166 says this is a race or a partial flush, not a deterministic path.

The other two phantoms sit between entities created at 2025-10-11T02:30:51Z and 2025-10-11T02:34:35Z, so they were allocated in a four-minute window that day and were already stale by the oldest backup. No snapshot old enough exists to watch them die. Note that `2107`'s uuid, `00de0000-0000-0000-0000-00000000de00`, is patterned rather than random and does not appear anywhere in the Logseq source.

## Other in-scope graphs (answered 2026-09-18)

Probed on copies: `CRM-Simple`, `Demo-Graph`, `GTD-02`, `cliworker`, `plugin-test`, `do-sync` and one further local graph — **all clean**, zero phantom entries and zero entities missing `:block/tx-id`. `Library-Test` is the only affected graph.

`test-rtc` could not be probed, for an unrelated and more serious reason: **its `db.sqlite` is corrupt at the SQLite level**, on the live file as well as on a `.backup` copy, with or without the WAL replayed.

```
$ sqlite3 db.sqlite "PRAGMA integrity_check;"
*** in database main ***
Tree 2 page 5 cell 0: invalid page number 248
Page 217: never used
Page 218: never used
```

Tree 2 is the `kvs` table itself. Exactly one row is unreadable — `addr 0`, the datascript storage root — so nothing can load the graph at all; the other 2,078 rows read fine. This is a separate bug from db-test#1214, and the file has **not been touched**, per the read-only rule for synced graphs.

The user confirmed on 2026-09-18 that the desktop app fails on it too, and the app's worker log gives the same SQLite error rather than the Library-Test one:

```
[db-worker/get-dbs-open] {:repo "logseq_db_test-rtc", :db-path "…/test-rtc/db.sqlite"}
db-worker-node failed to start: database disk image is malformed
```

**The live file cannot be revived.** `sqlite3 .recover` on a copy rebuilds a structurally clean database with all 2,079 rows, but `addr 0` comes back with **zero-length content** — the root row's data is gone, not merely unreachable. Rebuilding a datascript storage root by hand from the surviving nodes is not worth attempting.

**A clean restore point exists.** All 12 snapshots in `~/logseq/graphs/test-rtc/backups/` pass `integrity_check`, as do the stray `db.sqlite.pre-export` and `db.sqlite.unflushed` (`db.sqlite.corrupted`, from 2026-02-07, does not — this graph has been corrupted before). The newest, `2026-04-30T00_20_00.471Z.sqlite`, is **contemporaneous with the broken file**: it was written at 19:20 local on 2026-04-29 and the live `db.sqlite` was last written at 19:29, nine minutes later. On a copy it opens on the current build (224 pages, exit 0) and has zero phantom index entries.

### Restored, 2026-09-18

The user approved it. The broken `db.sqlite`, its `-wal`, its `-shm` and `client-ops-/db.sqlite` were copied to `~/logseq/graph-backups/test-rtc-broken-20260918T204400Z/` with checksums, then the 2026-04-30 snapshot was installed the same way as on `Library-Test`: copy in under a temporary name, delete the old `-wal`/`-shm` so the database and its WAL could not be mismatched, rename into place.

The graph opens on the current build: **224 pages, 48 tags, 49 tasks, 3 assets, 1,151 entities**, `integrity_check` → `ok`, zero phantom index entries and zero entities missing `:block/tx-id` after the startup migration ran.

**Sync was deliberately not started.** `logseq sync status` on the restored graph reports `ws-state stopped`, `graph-id -` and **`pending-local 9`** — nine local ops queued in `client-ops-`, which belong to the _newer_ state that was lost with the broken file. Pushing those against a snapshot that predates them is the obvious way to make things worse. A remote `test-rtc` does exist, with this account as `manager`, so the clean next step is `logseq sync download` into a **separate** graph, compare it with the restored one, and only then decide which becomes the local copy and what to do with the nine pending ops.

### Superseded: the restore would not sync

The restore did not hold. Sync refused to work against a snapshot that predated the server's state, so on 2026-09-18 the user deleted the local graph and re-downloaded it with Logseq sync. Sync works again, and the re-downloaded `db.sqlite` is structurally sound — `integrity_check` → `ok`, 1,162 entities, zero phantom index entries. The backups above are kept, but the restored file is no longer the live one.

### A third defect: RTC-downloaded graphs skip their migrations

The re-downloaded `test-rtc` still threw, with an error unlike either of the other two:

```
{:db-worker-node-invoke-failed
 {:status 500 :code :exception
  :error "Attribute :logseq.property/deleted-at should be marked as :db/index true"
  :data {:error :index-access :index :avet
         :components [:logseq.property/deleted-at nil nil nil]}
  :method :thread-api/get-render-snapshots}}
```

`logseq.db.common.view/get-exclude-page-ids` (`deps/db/src/logseq/db/common/view.cljs:334`) scans `(d/datoms db :avet :logseq.property/deleted-at)`. The graph's stored schema had no such attribute, so every render-snapshot call failed.

**The graph claimed schema-version 65.33 while three migrations had never run.** From `src/main/frontend/worker/db/migrate.cljs`:

| Migration | Declares                                                                                     | State in the download |
| --------- | -------------------------------------------------------------------------------------------- | --------------------- |
| 65.23     | `:logseq.property.asset/align`                                                               | missing               |
| 65.24     | `deleted-at`, `deleted-by-ref`, `recycle/original-parent`, `original-page`, `original-order` | all 5 missing         |
| 65.25     | deletes `:logseq.property.embedding/hnsw-label-updated-at`                                   | still present         |

Every built-in property is created through `sqlite-util/build-property`, which always sets `:db/index true`, so this is not a property that was built wrong — it was never built at all.

**`do-sync` has the identical defect**, and it is the other RTC graph. `cliworker` and all six local graphs (`CRM-Simple`, `Demo-Graph`, `GTD-02`, `plugin-test`, `Library-Test` and one further) are correct. That points at the sync/download path writing the current schema-version without running the migrations that go with it.

#### Repair: rewind the schema version and let upstream migrate

`spike/repair_rewind_schema_version.cljs` sets `:logseq.kv/schema-version` back to a chosen version — here `{:major 65 :minor 22}` — so the worker re-runs 65.23 onward on the next open, using upstream's own migration code rather than hand-built entities. **It writes to the graph it is given, so it runs on a copy first.**

Proven on a copy of the download, then applied to the live graph on 2026-09-18 with the user's go-ahead, once they had quit Logseq so the app released it. The leftover `db-worker.lock` named pid 6212, which was gone — checked before touching anything. Backup of `db.sqlite`, `-wal`, `-shm` and `client-ops-/db.sqlite` at `~/logseq/graph-backups/test-rtc-pre-migration-rewind-20260918T231901Z/`.

The live result matches the copy exactly:

- **+6** built-ins — `deleted-at`, `deleted-by-ref`, `asset/align` and the three `recycle/original-*` — each with `:db/index true`;
- **−1** deprecated `:logseq.property.embedding/hnsw-label-updated-at`, 65.25 finally applying;
- schema-version back to `{:major 65 :minor 33}`, the worker log stepping up to `DB schema migrated to {:major 65, :minor 33}`;
- `integrity_check` → `ok`, 1,168 entities, zero phantoms, 245 pages, unchanged.

**Nothing was pushed.** Under the CLI, `sync status` stays `ws-state stopped`, but the migration's writes are queued: `pending-local` went from 9 to **12**. They will push when the graph is next opened in the app — which is the intended outcome, since the server's copy is missing these built-ins too and every client that downloads it hits the same error.

**`do-sync` was repaired the same way on 2026-09-18**, backup at `~/logseq/graph-backups/do-sync-pre-migration-rewind-20260918T235035Z/`. Its profile was identical — 65.23 and 65.24's built-ins absent, 65.25's deletion not applied, everything from 65.26 on present, and its `initial-schema-version` is 65.22, so the graph was created right before the gap. The dry run on a copy and the live result agree: +6 built-ins with `:db/index true`, −1 deprecated `hnsw-label-updated-at`, back to 65.33, `integrity_check` ok, 206 pages unchanged, zero phantoms. Sync stayed `stopped` under the CLI; `pending-local` is 12, to be pushed when the app next opens it.

A sweep over all nine in-scope graphs now shows every one carrying the 65.23/65.24 built-ins and zero phantom index entries.

**Still to do:** none of the three defects beyond db-test#1214 is reported upstream.

## Repair: rebuilding the indexes works (proven on a copy, 2026-09-18)

Replaying every `:eavt` datom into a fresh storage-backed conn rebuilds all indexes from one set of facts, so a stale AVET entry cannot survive:

```
{:source-datoms 13619 :source-uuid-index 1215}
{:out-datoms 13606 :out-uuid-index 1212 :stale-index-entries 0}
```

Results on the copy:

- the rebuilt graph **opens on the current build** (`logseq --root-dir … list page` → 351 pages, exit 0), where the original still fails;
- the `[e a v]` fact sets of source and rebuild are **identical in both directions** — nothing was lost or invented. The 13-datom difference is duplicate datoms in the source that differ only by `tx`, which collapse on replay;
- entity ids, uuids and `:block/tx-id` values are preserved, because they are replayed as plain facts. What the rebuild does **not** preserve is datascript transaction identity: every datom lands in one new tx, so `:max-tx` and per-datom `tx` are rewritten. Nothing in Logseq appears to read them (`:block/tx-id` is a separate, preserved attribute), but it is the one real difference;
- `logseq graph validate` on the rebuilt copy reports 1 pre-existing entity error on the built-in `:block/tags` property. That is noise: `Demo-Graph` (4 errors) and `CRM-Simple` (5 errors) fail validation the same way, and the fact-set diff proves the rebuild did not introduce it.

The script is `spike/repair_rebuild_indexes.cljs`. It **only ever writes a new file**; it never opens the source for writing.

### Applied to the live graph, 2026-09-18

The user approved it. What was done, in order:

1. `logseq graph backup create` **does not work here** — it starts a worker, so it fails with the very error being repaired. The backup was taken at the file level instead: `db.sqlite` + `db.sqlite-wal` copied to `~/logseq/graph-backups/Library-Test-pre-index-rebuild-20260918T202058Z/`, with `SHA256SUMS.txt` recording that source and copy match.
2. Confirmed no worker held the live graph and no `db-worker.lock` existed.
3. Rebuilt from a fresh copy of the backed-up pair: 13,619 source datoms → 1,215 index entries in, 1,212 out, 0 stale. Fact diff against the source: **0 in either direction**.
4. Opened the rebuilt file with the current CLI in a scratch root — 351 pages, exit 0. That first open ran the startup migration, which added the 43 `:block/tx-id` values that were legitimately missing; the diff against the source is exactly those 43 additions and nothing else. `PRAGMA wal_checkpoint(TRUNCATE)` then `integrity_check` → `ok`.
5. Installed it: removed the live `db.sqlite-wal`, copied the rebuilt file in as `db.sqlite.new` and renamed it into place, so the database and its WAL could never be mismatched.
6. Verified against the live graph: `list page` → 351, `list tag` → 29, `list task` → 3, `list asset` → 1, and `[:find (count ?e) :where [?e :block/uuid]]` → **1,212**, the pre-repair entity count. Worker stopped cleanly afterwards.

Note for anyone repeating this: a _failed_ open still writes to the graph's WAL. The first `graph backup create` attempt grew `db.sqlite-wal` from 8.2 KB to 16 KB before the worker died, so back up the `.sqlite` and `.sqlite-wal` together, after any failed attempt, not before.

**Rollback:** restore both files from `~/logseq/graph-backups/Library-Test-pre-index-rebuild-20260918T202058Z/` and delete any `db.sqlite-wal`/`db.sqlite-shm` alongside the restored file.

## How to re-check (read-only)

Work on a **copy**, never the live file:

```sh
mkdir -p /tmp/libtest/graphs/Library-Test
cp ~/logseq/graphs/Library-Test/db.sqlite ~/logseq/graphs/Library-Test/db.sqlite-wal /tmp/libtest/graphs/Library-Test/
cd ~/Projects/src/github.com/logseq-graph-merge
pnpm exec nbb-logseq -cp src spike/check_orphan_datoms.cljs /tmp/libtest/graphs Library-Test
```

The probes, all read-only unless stated:

| Script                                    | What it answers                                                                       |
| ----------------------------------------- | ------------------------------------------------------------------------------------- |
| `spike/check_orphan_datoms.cljs`          | counts, the phantom entries, `max-tx`                                                 |
| `spike/probe_phantom_context.cljs`        | incoming refs to the phantoms, nearest real entities by eid (dates them)              |
| `spike/probe_eid_history.cljs`            | what given eids looked like in a given snapshot                                       |
| `spike/probe_deleted_page_leak.cljs`      | of a page's entities in an old snapshot, how many leaked index entries in a newer one |
| `spike/repair_rebuild_indexes.cljs`       | **writes a new file**: index-rebuild repair                                           |
| `spike/probe_rebuild_diff.cljs`           | `[e a v]` diff between two graphs                                                     |
| `spike/probe_attr_index_flags.cljs`       | whether a graph's stored schema marks given attributes `:db/index true`               |
| `spike/probe_schema_version.cljs`         | a graph's stored schema-version kv entries                                            |
| `spike/probe_ident_diff.cljs`             | `:db/ident` entities one graph has and another lacks                                  |
| `spike/repair_rewind_schema_version.cljs` | **writes in place**: rewind the schema version so migrations re-run                   |

They need `better-sqlite3`, which is symlinked into this project's `node_modules` from `../logseq/deps/db/node_modules`; recreate that symlink if it is missing. A graphs dir laid out as `<root>/graphs/<name>/db.sqlite` can also be driven by the CLI with `logseq --root-dir <root>`, which is how the failure and the repair were checked end to end.

## Graph facts

Local DB graph (no RTC graph uuid), `schema-version {:major 65 :minor 33}`, initial `{:major 65 :minor 1}`, created ~2025, `db.sqlite` about 2 MB, 1,212 entities, 158 page-parent links, 1 asset. It is one of the in-scope development graphs.

## Open questions

1. **Which deletion path drops the index entry?** Known now: it happens on delete, it is rare (1 in 166 in the one case that is fully dated), and it survives every later write. Not yet traced to code. The worker's delete path plus datascript's storage flush is where to look; the 2026-01-05 → 2026-01-12 window has no worker log (the oldest kept log is 2026-07-26).
2. ~~Apply the repair to the live graph?~~ **Done 2026-09-18**, backup kept at `~/logseq/graph-backups/Library-Test-pre-index-rebuild-20260918T202058Z/`.
3. **Does upstream want to tolerate stale entries anyway?** db-test#1214 argues the startup backfill should skip index entries with no entity, which would unbreak every affected graph without a rewrite. The new evidence (deletion remnants, rare, long-lived) is worth adding to that issue — the user has parked that for now.
4. **`test-rtc`'s corruption** is unexplained and unreported. It is moot for that graph now — the local copy was deleted and re-downloaded — but the cause was never found.
5. ~~Does the desktop app now open the graph?~~ **Yes** — the user confirmed it opens normally in the app, 2026-09-18. Nothing is left open on this graph.
6. **RTC downloads skip their migrations** (65.23, 65.24, 65.25 at least) while recording the current schema-version. Both affected graphs, `test-rtc` and `do-sync`, are repaired; the defect itself is unreported and unfixed. The server's copies presumably lack the built-ins too, so this hits every client that downloads one of these graphs — and a fresh download would reintroduce it.

## Links

- Upstream report: [db-test#1214](https://github.com/logseq/db-test/issues/1214)
- Requirements trap 17, and traps 18–19 found in the same work: [requirements.md](requirements.md)
- Task: T5.15 in [TASKS.md](TASKS.md)
