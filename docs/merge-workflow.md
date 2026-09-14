---
title: Logseq DB graph merge — workflow
status: draft
lastVerified: 2026-09-14
verifiedScope: matches the implementation in src/graph_merge (stages S1–S10 plus S1b, main.cljs phases) as of the first end-to-end merge, 2026-09-13
---

# Merge workflow

The diagrams below show how the merge works. `R*` and `D*` labels refer to [requirements.md](requirements.md).

The merge runs in six phases:

1. Preflight
2. Extract
3. Plan
4. Validate
5. Write
6. Verify

Phase 3, **Plan**, is made only of pure functions. It takes the source export maps and returns one merged export map plus a report, with no I/O. The planner is where all merge rules live and where the TDD tests focus.

## 1. End-to-end pipeline

```mermaid
flowchart TD
    CMD["merge --sources G1,G2,… --dest D<br/>[--dry-run] [--config-from G]"] --> PRE

    subgraph PRE["1 · Preflight (R2)"]
        direction TB
        P1["Every source graph exists"] --> P2["Destination D does not exist"] --> P3["All sources share one schema-version"]
    end

    PRE -->|any check fails| ABORT(["Abort: nothing written"])
    PRE --> EXT

    subgraph EXT["2 · Extract: per source, read-only"]
        direction TB
        E1["logseq graph export --type edn<br/>:graph-human, :include-timestamps? true"] --> E2["Page identity query:<br/>uuid, title, created-at, parent, order"]
        E2 --> E4["Asset query:<br/>uuid, checksum, type"]
        E4 --> E3["Collect config.edn and custom.css<br/>from ::graph-files"]
    end

    EXT --> PLAN["3 · Plan: pure functions<br/>see §2 Planner stages"]
    PLAN --> ART[/"merged.edn + report.edn"/]
    ART --> VAL

    subgraph VAL["4 · Validate (D5)"]
        direction TB
        V1["validate-export into an in-memory graph"] --> V2["Every asset file exists<br/>and its SHA-256 matches"]
    end

    VAL -->|errors| ABORT
    VAL --> DRY{"--dry-run?"}
    DRY -->|yes| PRINT(["Stop: merged.edn and report.edn<br/>written, no graph touched"])
    DRY -->|no| WRITE

    subgraph WRITE["5 · Write"]
        direction TB
        W2["logseq graph import --type edn<br/>--input merged.edn --graph D<br/>(creates D)"] --> W3["Copy asset files uuid.ext into D"]
    end

    WRITE --> VER

    subgraph VER["6 · Verify (D5)"]
        direction TB
        C1["Query D: every exported page exists with<br/>at least its blocks; copied asset checksums match"] --> C2["logseq graph validate --graph D"]
    end

    VER -->|mismatch or invalid| FAIL(["Fail: keep report,<br/>delete D and re-run (D6)"])
    VER --> DONE(["Done"])
```

The post-import count check is required: in the T3 spike, `graph import` exited 0 and printed "Imported" even though it had rejected the EDN and written nothing (trap 6).

The two queries in Extract exist because `:graph-human` drops page parents, page orders and unreferenced uuids (traps 4 and 9). S1 uses the query results to put those back.

## 2. Planner stages

Each stage is a pure function in its own namespace under `src/graph_merge/stage/`, composed by `graph-merge.plan/plan`. The stages run in this order because later stages use the maps built by earlier ones.

```mermaid
flowchart TD
    IN[/"Source export maps, in source order<br/>+ asset lists + config files"/] --> S1

    S1["S1 · Normalize and recover identities (R2a)<br/>drop ::kv-values, ::property-history<br/>set ::graph-files aside for S9<br/>drop $$$views, Recycle, deleted nodes<br/>union duplicate built-ins in a source<br/>page uuid by (title, created-at), asset uuid by checksum"]
    S1b["S1b · Values the importer rejects (R2b)<br/>drop empty number values<br/>invalid choices and refs to deleted pages<br/>become 'Property: value' note blocks"]
    S2["S2 · uuid collisions (R9)<br/>first occurrence keeps its uuid<br/>later ones get uuid-v5(graph, uuid)"]
    S3["S3 · Unify properties (R3)<br/>see §3"]
    S4["S4 · Unify tags / classes (R4)<br/>see §3"]
    S5["S5 · Rewrite idents (R8)<br/>property keys, tags, class-properties,<br/>property-classes, view settings"]
    S6["S6 · Assets (R10)<br/>force-keep asset uuids<br/>dedupe by checksum: first wins"]
    S7["S7 · Match and merge pages (R5, R6, R6b, R7)<br/>restore page parents and fresh sibling orders<br/>see §4"]
    S8["S8 · Rewrite uuid refs (R8)<br/>[[uuid]] and #[[uuid]] in titles<br/>[:block/uuid …] property values"]
    S9["S9 · Graph Merge page (R12)<br/>per-source config.edn and custom.css code blocks<br/>+ Merge report block"]
    S10["S10 · Sort and emit (D1, D2)<br/>journals by day, pages by normalized title<br/>blocks keep source order"]

    S1 --> S1b --> S2 --> S3 --> S4 --> S5 --> S6 --> S7 --> S8 --> S9 --> S10

    S2 -.->|uuid re-key map| S8
    S3 -.->|property ident map| S4
    S3 -.->|property ident map| S5
    S4 -.->|class ident map| S5
    S6 -.->|asset uuid map| S8
    S7 -.->|page uuid map| S8

    S10 --> OUT[/"merged.edn + report.edn"/]
```

Every stage appends its decisions (renames, remaps, merges, skips, re-keys) to the shared report. S9 renders that report into the `Graph Merge` page.

## 3. Ontology unification (S3 and S4)

```mermaid
flowchart TD
    START["All user properties from all sources<br/>(built-in logseq.* idents pass through unchanged)"] --> GRP["Group by normalized title<br/>(trim, case-insensitive)"]
    GRP --> FIRST["First member by source order is canonical:<br/>its ident, schema and closed values are kept"]
    FIRST --> NEXT{"Next member<br/>in the group?"}
    NEXT -->|no| CLS
    NEXT -->|yes| COMPAT{"Same type and<br/>same cardinality?"}
    COMPAT -->|yes| MAP["Map member ident to canonical ident<br/>union closed values by title<br/>report other schema differences"]
    COMPAT -->|no| REN["Rename to 'title (graph)'<br/>new ident derived from title + graph slug<br/>map member ident to new ident; report"]
    MAP --> NEXT
    REN --> NEXT

    CLS["All user classes from all sources"] --> CGRP["Group by normalized title"]
    CGRP --> CFIRST["First member by source order is canonical<br/>map the other idents to it"]
    CFIRST --> CPROPS["class-properties: ordered union<br/>(after property ident map)"]
    CPROPS --> CEXT["extends: union of parents"]
    CEXT --> CYC{"Cycle in extends?"}
    CYC -->|yes| CFALL["Use the canonical member's extends; report"]
    CYC -->|no| CDONE(["Ident maps ready for S5"])
    CFALL --> CDONE
```

## 4. Page matching (S7)

Pages are **grouped first and resolved second**. The result therefore does not depend on which source a tagged or untagged page came from. Source order only decides precedence: which uuid, title spelling and page properties win.

```mermaid
flowchart TD
    ALL["All pages from all sources<br/>(after S1–S6)"] --> KIND{"Page kind?"}

    KIND -->|journal| JKEY["Group by journal day (R5)"]
    JKEY --> JMERGE["One page per day<br/>blocks: source 1, then source 2, …<br/>page properties: first wins"]

    KIND -->|$$$favorites| FAV["Union of favorite links<br/>deduped by target (R7), drops reported"]
    KIND -->|"tag/property page {:block/uuid}"| DEF["Group by uuid through the<br/>S3/S4 definition uuid map"]
    DEF --> MAPS

    KIND -->|"page, Contents, Library child"| PKEY["Group by normalized title<br/>+ parent path (R6)"]
    PKEY --> CLASS{"Top-level page titled like a merged class,<br/>or exactly like a built-in tag/property?"}
    CLASS -->|yes| TOCLS["Append the page's blocks<br/>to that definition's page (R4)"]
    CLASS -->|no| SPLIT["Split the group into tagged<br/>and untagged pages"]

    SPLIT --> CLUSTER["Cluster tagged pages whose tag sets overlap<br/>(transitive: A∩B and B∩C puts A, B, C together)"]
    CLUSTER --> COUNT{"Number of tagged clusters?"}
    COUNT -->|exactly 1| INTO["Untagged pages join that cluster"]
    COUNT -->|0 or 2+| OWN["Untagged pages form their own cluster"]

    INTO --> EMIT
    OWN --> EMIT
    EMIT["For each cluster, emit one page:<br/>uuid and title from the earliest member<br/>tags: union · properties: first wins<br/>blocks: appended in source order"]
    EMIT --> SEP["Every emitted page gets an explicit kept :block/uuid<br/>+ :block/parent and a fresh :block/order (R6b)"]

    JMERGE --> MAPS(["page uuid map → S8<br/>merges and clusters → report"])
    FAV --> MAPS
    TOCLS --> MAPS
    SEP --> MAPS
```

Examples for one title and parent path:

| Source pages | Result |
|---|---|
| `Apple` (G1), `Apple` (G2) | 1 page, G1 blocks then G2 blocks |
| `Apple #Fruit` (G1), `Apple` (G2) | 1 page `Apple #Fruit`: one tagged cluster, so the untagged page joins it |
| `Apple` (G1), `Apple #Fruit` (G2) | Same as above; order doesn't matter |
| `Apple #Company` (G1), `Apple #Fruit` (G2) | 2 pages, kept separate |
| `Apple #Company` (G1), `Apple #Fruit` (G2), `Apple` (G3) | 3 pages: two tagged clusters, so the untagged page stays on its own |
| `Apple #Fruit` (G1), `Apple #Fruit #Food` (G2) | 1 page `Apple #Fruit #Food` |
