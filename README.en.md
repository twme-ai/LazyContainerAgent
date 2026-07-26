# LazyContainerAgent

[中文](README.md) ｜ **English**

> **A Java agent that lazily deserializes container items, and writes untouched containers back byte-for-byte.**
> Supports **Paper 26.2, 26.1.x, 1.21.11, 1.21.8, 1.20.6, and 1.19.4**. It removes two pieces of wasted work that cause Minecraft server lag: unpacking every chest's items from NBT on chunk load, and re-packing them on unload.

> *Keywords: Minecraft, Paper, performance, lag, TPS, chunk loading, container/chest/barrel/shulker box, NBT deserialization, data components, map art, Java agent, ASM, optimization, spark profiler.*

🧠 The original single-version 26.2 implementation underwent a 49-agent Claude Fable 5 adversarial audit (see [`FABLE5-AUDIT.md`](FABLE5-AUDIT.md)) and fixed one admin-command-only data-aliasing bug. **That report is historical design evidence and does not cover this multi-version rewrite.** The rewrite is validated by real bytecode tests for four NMS layouts and seven Paper runtime round-trips.

⚠️ **This is a Java agent, NOT a plugin** — attach it with `-javaagent:`. **Do not drop it in `plugins/`** (it does nothing there).

> 🔒 **Version-sensitive (read first)**
> This agent weaves bytecode directly into Paper internals. One jar contains four templates compiled against real Paper NMS; it selects one by exact class and method descriptors, not by trusting a version string.
> - Tested runtime combinations: Paper **26.2 / 26.1.2 / 26.1.1 on Java 25**, **1.21.11 / 1.21.8 / 1.20.6 on Java 21**, and **1.19.4 on Java 17**.
> - Paper does not publish a standalone `26.1` download; `26.1.x` here means the tested `26.1.1` and `26.1.2` releases.
> - An unknown NMS layout logs `unsupported NMS layout` and stays entirely vanilla. A leaf with any missing interception point is also left unchanged, preventing a partial rewrite.

---

## Quick start

> Requires one of the tested Paper / Java combinations above.

**1. Drop the jar** somewhere the node can see it (next to your server jar is easiest). **Not** in `plugins/` — it's a Java agent, not a plugin.

**2. Add the flag** on the `java` line, **before** `-jar` (use shadow-validation mode the first time):

```bash
java ... \
  -javaagent:LazyContainerAgent.jar \
  -Dlazycontainer.shadow=true \
  -Dlazycontainer.verbose=true \
  -jar <your-Paper>.jar nogui
```

**3. Validate first — don't jump straight to performance.** Run with `shadow=true` for a few days. It byte-compares the optimized output against vanilla; as long as `shadowMismatch` stays **0**, the output is byte-identical to vanilla — **zero data risk**. (Trade-off: shadow mode runs both paths, so no speedup yet.)

**4. Switch to real performance.** Once `shadowMismatch=0` holds for a few days and nobody reports missing items, remove `-Dlazycontainer.shadow=true` and restart. Untouched containers are now written back as-is, skipping the re-pack.

**Rollback:** remove the flags and restart → back to 100% vanilla, **no data migration** (the on-disk format was never changed).

---

## What it solves

Run a server long enough and you hit a strange kind of lag: barely anyone online, yet the main thread is busy. Profile it with spark and the culprit is often **chests** — or rather, the items inside them.

Minecraft stores items packed/compressed on disk. Every time a chunk loads, the server **unpacks every item in every container in that chunk from NBT**; when the chunk unloads, it **packs them all again**. But the vast majority of containers are **never opened** between load and unload — pure wasted work. Since 1.21, items carry data components (enchantments, lore, custom names, nested container contents), making pack/unpack even more expensive. A warehouse full of map art, or a storage line packed with shulker boxes, can eat a large fraction of the main thread just from being loaded.

The lazy approach, in one sentence:

> **Don't unpack a container nobody is looking at; write an untouched container back as-is.**

On load, keep the raw item bytes stashed and skip the decode. Only when something actually accesses the container — a player opens it, a hopper pulls, a comparator reads — is that **one** container decoded. Containers untouched from load to unload are written back byte-for-byte, skipping the re-encode.

To the rest of the game it behaves exactly like vanilla — same items, same slots, indistinguishable. The only difference is on the server: all that "unpack-and-repack that nobody looked at" work is gone.

---

## Benchmark: a ~206-deep "hadouken" callstack → 0%

Loading one chest of map art, just to decode the items from NBT, produces a callstack roughly **206 frames** deep — because the data is genuinely nested (chest → shulker box → map → lore → multi-colored text) multiplied by Mojang's codec combinator framework (~15–20 frames per field). The deepest frame is merely `TextColor.parseColor` / `String.equals`.

<p align="center">
<img src="docs/img/callchain-206-before.png" width="360" alt="206-deep callstack (hadouken)">
&nbsp;&nbsp;
<img src="docs/img/improved-after.png" width="440" alt="after">
</p>

Same dense-container chunk, `forceload` churn, two runs:

| Run | Mode | spark | container decode (main thread) | profile nodes | deepest call |
|---|---|---|---|---|---|
| 1 | vanilla | `WOVkupfiJx` | **62.17%** | 3378 | 200 |
| 1 | **agent** | `wGDbUTbZKN` | **0.00%** | 482 | 9 |
| 2 | vanilla | `AjXLAdXzTd` | **65.65%** | 4305 | 200 |
| 2 | **agent** | `caXFofKSVQ` | **0.00%** | 763 | 36 |

The entire decode tower **disappears** from the agent profile. Static spark profiles (saved so the links can't expire) + the full 206-line callstack + notes: [`docs/spark/`](docs/spark/SUMMARY.md).

> The 62–66% is an **isolated** stress test (containers only). On a real mixed-load server it is ~24% on load + ~11% on unload (up to ~45% on the heaviest node). It saves the pack/unpack CPU — not disk I/O or GC.

**Real-world login test** — a player's storage area of ~6,000 chests holding ~750,000 filled maps, copied to a test server:

| | Vanilla | Agent |
|---|---|---|
| Loading the area | main thread **frozen 25–30 s** (watchdog) | no decode freeze |
| Player login | **repeatedly kicked (lost connection)**, couldn't get in | **single clean login** |
| Item deserialization | all 750k maps decoded → crash | **`ensure=0` — not a single one decoded** |

---

## How it works

Like a moving company that used to open and reseal every box passing through the warehouse — even the ones nobody asked about. Now: don't open what nobody wants; ship the untouched ones sealed.

It injects the NMS container classes, adds two synthetic fields, and rewrites the access points:

| Action | Counter | What it does |
|---|---|---|
| **Lazy load** | `stash` | `loadAdditional` does not call `ContainerHelper.loadAllItems`; it stashes the raw, undecoded `Items` ListTag and marks `pending`. **Skips decode.** |
| **Materialize on access** | `ensure` | The first call to `getItems()/getContents()` decodes the stashed raw into the list (only that one container). |
| **Write back as-is** | `rawSave` | On save, if the container was never touched (`pending`), the original bytes are written back byte-for-byte. **Skips encode.** |
| **Eager fallback** | `eagerLoad` | If the input isn't `TagValueInput` (shouldn't happen) → safe fallback to vanilla behavior. |

Covers `ChestBlockEntity`, `BarrelBlockEntity`, `ShulkerBoxBlockEntity`. The **single chokepoint is `getItems()`** — every container read/write in NMS `BaseContainerBlockEntity` goes through it, so guarding one point covers them all; CraftBukkit's `getContents()` bypasses it and is guarded separately.

### Architecture

A plain plugin can't override NMS (Minecraft's internal server code) methods marked `final` (methods a subclass is forbidden to override), so this uses a **Java agent + ASM bytecode injection** (ASM = a library for reading and rewriting compiled Java bytecode at class-load time):

1. `LazyContainerAgentMain` (`premain`, the very first entry point the JVM runs at startup): appends the whole jar to the **bootstrap classloader** (the common ancestor of every classloader in the JVM — this is what lets it get past Paper's isolated classloader for Minecraft's internal classes), then registers the transformer (the class-rewriter from step 2).
2. `LazyContainerTransformer` (the ASM logic that does the actual rewriting): identifies the value-io, registry-nbt, legacy Mojang, or legacy Spigot API from the base class's real signatures, then splices the matching `LazyContainerTemplate`. Each template is plain Java compiled against real Paper NMS, so `javac` verifies its calls. It also redirects load/save helper calls inside the three leaf classes (chest, barrel, shulker box) to the lazy versions.
3. `LazyContainerRuntime` (lives on the bootstrap classloader, pure JDK with zero Minecraft dependencies): the shadow-mode toggle + counters.
4. **Safety rule:** if splicing the base class fails, the leaf classes are left **completely untouched** → falls back to pure vanilla behavior, so it can never produce a "method not found" crash (`NoSuchMethodError`); any exception during the process → the original, unmodified bytecode is returned.

---

## Why it doesn't lose or alter data

It changes **when** items are decoded, not **what** a container stores. The on-disk format never changes.

- **Untouched containers** → the exact bytes read on load are written back (byte-identical), with no transformation.
- **Touched containers** → decoded and saved exactly like vanilla.
- Only the container's `Items` (the item list stored inside it) are affected — never terrain, blocks, entities, lighting, or other block entities (the objects attached to a block that hold its extra data, e.g. a sign's text or a chest's contents).

Validated by: real-NMS bytecode tests for all four layouts; two-boot Paper round-trips on 26.2, 26.1.2, 26.1.1, 1.21.11, 1.21.8, 1.20.6, and 1.19.4, including a same-instance reload without `Items` (every run: `stash=5 ensure=2 rawSave=8 eagerLoad=0 shadowMismatch=0`); and the original 26.2 data-component and 56-container shadow tests. The adversarial reports remain useful historical context for the original implementation. See [`docs/test-reports/multi-version.md`](docs/test-reports/multi-version.md), [`docs/test-reports/26.2.md`](docs/test-reports/26.2.md), [`FINDINGS.md`](FINDINGS.md), [`ADVERSARIAL-REVIEW.md`](ADVERSARIAL-REVIEW.md), and [`FABLE5-AUDIT.md`](FABLE5-AUDIT.md).

---

## shadow mode (run this before going live)

`-Dlazycontainer.shadow=true`: before writing raw, it **also** runs vanilla's encode and byte-compares the two.
- **Match** → write raw.
- **Mismatch** → write vanilla's version (safe), increment `shadowMismatch`, log the position.

With shadow on, the on-disk output is mathematically identical to vanilla — zero-risk validation. No speedup while it's on (both paths run). Run a few days at `shadowMismatch=0`, then turn it off for real performance.

### `shadowMismatch` vs `benignReorder`

A byte-strict compare false-positives on "same items, just a different `Items` list order" (common for plugin-written containers — each entry carries its own `Slot`, so list order doesn't affect placement). So the compare is split:

- **`benignReorder`** — same items and slots, only the list order differs (verified as a multiset) → safe to write raw, not a problem. Still detected and logged as `NO IMPACT`.
- **`shadowMismatch`** — a real structural difference (item count/content changed) → write vanilla's version + log.

Watch **`shadowMismatch=0`**. With `-Dlazycontainer.dump=true`, both kinds dump raw/eager SNBT for offline diff (`lc-mismatch-N` / `lc-benign-N`).

---

## Build

```bash
bash build.sh --prepare  # first build: fetch pinned Paper NMS, test, and package
bash build.sh            # later builds: reuse nms-lib/
```

Requires **JDK 21+, Maven, curl, jq, and sha256sum**. `--prepare` uses Paper's official Fill API, verifies SHA-256 checksums, and creates `nms-lib/`. That directory is not committed because it contains large Mojang/Paper artifacts.

Steps: ① compile the bootstrap agent as Java 17 bytecode; ② compile four templates against pinned real NMS as Java 21 / 17 bytecode; ③ run JUnit tests against the actual Paper classes, covering base splicing, all three leaves, and atomic rejection of partial matches; ④ shade ASM and package all four template classes as passive resources in one jar.

## Deployment

Drop the jar somewhere the node can see it, and insert the flags on the `java` line **before `-jar`**:

```bash
java -Xms8000M -Xmx8000M \
  -javaagent:LazyContainerAgent.jar \
  -Dlazycontainer.shadow=true \
  -Dlazycontainer.verbose=true \
  ... your existing -XX flags ... \
  -jar <your-Paper>.jar nogui
```

The boot log should show:
```
[LazyContainer] LazyContainerAgent —— crafted by 廢土貓大 LogoCat · 廢土 · mcfallout.net
[LazyContainer] agent installed (transformer registered) [SHADOW mode]
[LazyContainer] detected Paper ... (layout=...)
[LazyContainer] spliced ... fields + ... methods into ...
[LazyContainer] transformed leaf ...Chest... (load=1 save=1 getItems=1 getContents=1 setItems=1)
[LazyContainer] transformed leaf ...Barrel... (load=1 save=1 getItems=1 getContents=1 setItems=1)
[LazyContainer] transformed leaf ...ShulkerBox... (load=1 save=1 getItems=1 getContents=1 setItems=1)
```

## Flags

| Flag | Effect |
|---|---|
| `-Dlazycontainer.shadow=true` | **Required before going live.** Output guaranteed identical to vanilla; no speedup yet. |
| `-Dlazycontainer.verbose=true` | Background thread prints the counters periodically. |
| `-Dlazycontainer.verbose.ms=8000` | Counter interval (ms, default 30000). |
| `-Dlazycontainer.dump=true` | On mismatch / benign reorder, dump raw/eager SNBT (SNBT = the human-readable text form of NBT data) (`lc-mismatch-N` / `lc-benign-N`, first 30 each) for offline diff. |
| `-Dlazycontainer.dump.dir=<path>` | Dump output directory (default `.`, the server's working directory). |

**Rollback:** remove those flags and restart → back to 100% vanilla, no data migration needed (the on-disk format was never touched).

---

## Limitations

- **Benefit depends on containers being untouched.** Churn / idle storage (load → nobody touches → unload) wins big; a busy hopper/comparator sorting hall materializes containers, so the pure savings shrink (the main benefit there becomes "spread the load-time spike out"). The sibling project **ChunkForceManager** complements this from the other end (stop chunks from repeatedly loading/unloading in the first place).
- **Version-sensitive.** Only the listed Paper families and matching NMS layouts are supported. A future Paper build can change internals without changing its public version; confirm the detected layout and all three `transformed leaf` messages in a shadow-mode staging boot.
- Does **not** save disk I/O or GC — only the pack/unpack CPU.

---

## File map

```
src/main/java/io/github/kuohsuanlo/lazycontainer/
  LazyContainerAgentMain.java   premain / bootstrap classloader attachment
  LazyContainerRuntime.java     bootstrap, pure JDK: shadow toggle + counters
  NmsTarget.java                four NMS layouts, descriptors, and template mapping
  LazyContainerTransformer.java ASM: splices the base class + rewrites the leaves
templates/{value-io,registry-nbt,legacy-*}/...   four javac-verified splice sources
tools/prepare-paper-nms.sh      fetches, verifies, and patches pinned Paper NMS
tools/runtime-test.sh           real Paper two-boot round-trip test
tools/scan_containers.py        scans region files for the densest-container chunks (finds the most expensive spots to load)
build.sh  pom.xml  nms-lib/ (not committed to git)
FINDINGS.md           facts confirmed by decompilation + design rationale + risk analysis
ADVERSARIAL-REVIEW.md historical 26.2 adversarial review (8 failure modes, 12 agents)
FABLE5-AUDIT.md        historical 26.2 second-round adversarial audit (49 agents)
TESTING.md            how to test it yourself (automated round-trip / manual play-testing / shadow-validate against a real-world copy)
```

Further reading: [`FINDINGS.md`](FINDINGS.md) (full technical picture) · [`TESTING.md`](TESTING.md) (testing it yourself) · [`ADVERSARIAL-REVIEW.md`](ADVERSARIAL-REVIEW.md) (review) · [`FABLE5-AUDIT.md`](FABLE5-AUDIT.md) (Fable 5 audit).

---

Crafted by 廢土貓大 LogoCat · [mcfallout.net](https://mcfallout.net)
