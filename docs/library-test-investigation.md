---
title: Library-Test — unopenable on current Logseq builds
status: open
lastVerified: 2026-09-17
verifiedScope: reproduced live on CLI/worker 6bf8fe7-dirty and b09316a; root cause proven read-only on a copy of db.sqlite; filed as db-test#1214
---

# Library-Test: unopenable on current builds

Resume note for a fresh session. The merge tool is finished and unrelated to this; this file is the state of the Logseq-side investigation (task T5.15).

## Symptom

`Library-Test` cannot be opened by current builds, from the app or the CLI:

```
Error (server-start-failed): Worker exited before becoming ready; startup cleanup failed:
Worker close failed: Cannot store nil as a value at {:db/id nil, :block/tx-id 536885855}
```

- Stable `Logseq.app` (`b09316a`) **opens it normally**.
- Upcoming `Logseq-DB.app` (`f7362f0-dirty`, then `6bf8fe7-dirty`) **fails**. `f7362f0` reported only `server-start-timeout-orphan`; `6bf8fe7` reports the message above.
- The real error is always in `~/logseq/graphs/Library-Test/db-worker-node-<date>.log`, after `{:db-worker/on-become-master-start …}`.

## Root cause (confirmed)

`ensure-canonical-revisions!` (`src/main/frontend/worker/db_core.cljs:714-724`, unchanged on upstream master `8e15eeecdf`) walks the `:block/uuid` AVET index at startup and writes `:block/tx-id` to every entity:

```clojure
{:db/id (:db/id (d/entity db (:e datom))) :block/tx-id tx-id}
```

`Library-Test` has **3 index entries with no entity behind them**, so `:db/id` is nil and the transaction throws, killing the worker.

| | Count |
|---|---|
| `:block/uuid` datoms in the AVET index | 1,215 |
| Entities that exist (`[:find (count ?e) :where [?e :block/uuid]]` on `b09316a`) | 1,212 |
| Index entries whose `d/entity` is nil | 3 |
| Entities legitimately missing `:block/tx-id` (backfilled fine) | 43 |

The three phantom entries, each with no `:eavt` rows and no `:aevt` rows:

| eid | uuid |
|---|---|
| 2120 | `e7a8f0a0-8e11-4aea-b36d-8bbdae54b125` |
| 2107 | `00de0000-0000-0000-0000-00000000de00` |
| 2382 | `d5eccfd9-b5a6-4d1e-b17e-423d7a1e66ce` |

Introduced by `fb1047d1f8` ("refactor: data fetch && reactivity", 2026-07-20), which is not in `b09316a` — matching the build split above.

**Ruled out:** a stale lock. `db-worker.lock` does not exist, no server is running for the graph, and no process holds it.

## Graph facts

Local DB graph (no RTC graph uuid), `schema-version {:major 65 :minor 33}`, initial `{:major 65 :minor 1}`, created ~2025, `db.sqlite` about 2 MB, 1,212 entities, 158 page-parent links, 1 asset. It is one of the in-scope development graphs.

## How to re-check (read-only)

Work on a **copy**, never the live file:

```sh
mkdir -p /tmp/libtest/Library-Test
cp ~/logseq/graphs/Library-Test/db.sqlite ~/logseq/graphs/Library-Test/db.sqlite-wal /tmp/libtest/Library-Test/
cd ~/Projects/src/github.com/logseq-graph-merge
pnpm exec nbb-logseq -cp src spike/check_orphan_datoms.cljs /tmp/libtest Library-Test
```

`spike/check_orphan_datoms.cljs` prints the counts, the phantom entries and `max-tx`. It needs `better-sqlite3`, which is symlinked into this project's `node_modules` from `../logseq/deps/db/node_modules`; recreate that symlink if it is missing.

## Open questions for the next session

1. **How did the phantom entries appear?** Unknown. Worth checking: the graph's older worker logs (`db-worker-node-2026*.log`, back to May), whether it was ever RTC-synced or re-imported, and whether a crash lines up with their creation.
2. **Do other graphs have them?** The same probe can be run over copies of the other in-scope graphs; if more graphs are affected, that strengthens db-test#1214.
3. **Repair options**, all to be tried on a copy first and only applied with the user's go-ahead plus a backup:
   - re-persist: open the copy with a build that works (`b09316a`) and make a trivial write, then see whether the rewritten storage still carries the phantom entries;
   - rebuild: export with `b09316a` and import into a fresh graph (loses ids that the export drops, and hits db-test#1212's tag naming unless the merge tool's emit fix is used);
   - wait for upstream to tolerate stale entries (db-test#1214).
4. **Does the desktop app show the same failure**, not just the CLI? Expected yes, since both use the same worker; not verified.

## Links

- Upstream report: [db-test#1214](https://github.com/logseq/db-test/issues/1214)
- Requirements trap 17, and traps 18–19 found in the same work: [requirements.md](requirements.md)
- Task: T5.15 in [TASKS.md](TASKS.md)
