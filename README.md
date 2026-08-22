# VDRST

Low-latency viral sequence search. A k-mer index narrows NCBI-style reference genomes to a
few candidates, then each one is re-aligned exactly with a vectorised Smith-Waterman.

**p50 1.75 ms, p99.9 4.46 ms** against a 500-genome corpus — 27x faster than the version
this repository started as, and 14,000x faster than aligning against every genome.

Written in 2024 as my first project. Rebuilt in 2026 with benchmarks, tests, and
[a written retrospective](RETROSPECTIVE.md) of everything the first version got wrong. The
original is preserved at [`v1.1-2024-complete`](../../tree/v1.1-2024-complete).

Zero external dependencies. Java 21 and a JDK are the whole toolchain.

---

## Benchmarks

Each row changes exactly one thing from the row above it, so the difference between two
rows has a single cause. Reproduce with `make corpus && make bench` — the corpus is
generated from a fixed seed, so these numbers come back the same on any machine of similar
speed, with nothing to download.

| | prefilter | re-rank | p50 | p99 | p99.9 | vs B0 |
|---|---|---|---:|---:|---:|---:|
| **B0** | none | v1 Smith-Waterman | 25,302 ms | — | — | 1x |
| **B1** | blastn subprocess | v1 Smith-Waterman | 47.62 ms | — | — | 531x |
| **B2** | blastn subprocess | Gotoh affine | 47.63 ms | — | — | 531x |
| **B3** | k-mer index | Gotoh affine | 8.69 ms | 9.52 ms | 12.67 ms | 2,912x |
| **B4** | k-mer index | Gotoh banded | 7.94 ms | 10.18 ms | 12.59 ms | 3,187x |
| **B5** | k-mer index | Gotoh SIMD | **1.75 ms** | **2.88 ms** | **4.46 ms** | **14,475x** |

`B0` is the honest baseline: what "search the database with Smith-Waterman" costs when
nothing narrows the candidate set first. `B1` is the pipeline v1 actually shipped. `B5` is
what runs today — **27.2x faster than B1**.

<sub>OpenJDK 21.0.10, Linux x86-64, AVX-512 (16 int lanes), 2 vCPU. 500 synthetic genomes,
23,384,787 bases, seed `0x5EEDC0FFEE`. 50 queries of 300 bases. 2,000 iterations after 200
warmup for B3–B5; 50 for B1–B2; 3 for B0, which takes ~25 s per sample. Percentiles are
nearest-rank; a row reports only the percentiles its sample count supports.</sub>

### Where the time goes

```
k-mer prefilter             0.247 ms    13.8%
alignment (20 candidates)   1.537 ms    86.2%
```

It did not start out this way, and the reason it changed is the most useful thing in the
project. Before the subprocess was removed:

```
blast subprocess           49.89 ms    99.2%
smith-waterman re-rank      0.41 ms     0.8%
```

Alignment was 0.8% of a search. The plan had been to vectorise it. Breaking the other 99%
down:

```
/bin/true (bare process fork)          2.49 ms
blastn -version (fork + libraries)    31.21 ms
blastn, 20-base query (+ db open)     40.22 ms
blastn, 300-base query (real work)    39.87 ms
```

A trivial query and a real one cost the same — the similarity search does not appear in its
own latency budget. Roughly 29 ms was the OS loading the blastn binary and its shared
libraries and 9 ms was reopening the database, repeated identically on every request.

Replacing the subprocess with an in-process index took the prefilter from 49.89 ms to
0.135 ms and *inverted* the budget. Only then was vectorising the alignment worth writing.
[The long version](RETROSPECTIVE.md#13-i-optimised-the-wrong-thing-and-only-measurement-caught-it).

### Alignment kernels

A 300-base query against the 428-base window the prefilter produces:

| | p50 | p99 | throughput |
|---|---:|---:|---:|
| `legacy-v1` — v1's, full `int[][]`, linear gaps | 422.72 µs | **3562.76 µs** | 304 Mcells/s |
| `gotoh-affine` — affine gaps, rolling buffers | 425.01 µs | 645.09 µs | 302 Mcells/s |
| `gotoh-banded-64` — band from the seed diagonal | 349.06 µs | 391.89 µs | 368 Mcells/s |
| `gotoh-simd-16lane` — anti-diagonal, Vector API | **73.90 µs** | **132.85 µs** | 1738 Mcells/s |

The p99 column is the one worth reading twice. v1's median was competitive; its p99 was
8.4x its own median, because it allocated a full `int[301][429]` on every call and paid for
it later, in whichever request was unlucky enough to be running when the collector went.
Rolling buffers fixed the tail without moving the median at all.

---

## How it works

```
  sequence
     │
     ▼
  validate ─────────────── reject non-nucleotides, bound the length
     │
     ▼
  k-mer index ──────────── every 11-mer of the query looked up in a
     │                     direct-addressed CSR table; seed hits collected
     │                     by alignment diagonal; strongest diagonals win
     │                     ~0.14 ms, bounded work per query
     ▼
  20 candidates ────────── a window of each genome, cut around its diagonal
     │
     ▼
  Gotoh SIMD ───────────── exact affine-gap Smith-Waterman, vectorised
     │                     along anti-diagonals
     │                     ~1.5 ms for all 20
     ▼
  top 3
```

**The prefilter** is a direct-addressed k-mer table in CSR layout. With k = 11 there are
4^11 buckets, so the k-mer *is* the bucket number: no hashing, no collision handling, no
boxing. A lookup is one indexed read and a sequential walk. Seed hits are accumulated by
alignment diagonal in an open-addressed table of two `int[]`, retired between queries by a
generation counter rather than by clearing. Per-query scratch is per-thread and reused, so
a steady-state search allocates only its result list.

Work per query is bounded: a k-mer occurring more than 512 times in the database is a
repeat, distinguishes nothing, and is skipped. That costs a little sensitivity on
low-complexity input and buys a ceiling on the tail.

**The aligner** is Smith-Waterman with affine gap costs (Gotoh 1982), vectorised along
anti-diagonals rather than in Farrar's striped layout. The dependency that makes
Smith-Waterman awkward to vectorise runs along a row — `E[i][j]` needs `E[i][j-1]` — and
Farrar works around it with a fix-up pass whose cost depends on the data. Cells on one
anti-diagonal have no such dependency: everything a cell on diagonal *d* needs lives on
*d-1* or *d-2*. So an anti-diagonal is one pass of independent lanes, with no fix-up and no
data-dependent branch, and the arithmetic is identical to the scalar version.

That last point is what makes it testable. `AlignerEquivalenceTest` demands the vectorised,
banded and scalar implementations return the *same integer* as a deliberately naive
three-matrix reference, across randomised inputs and four scoring schemes. Without it,
"5.75x faster" and "5.75x faster and quietly wrong" look identical from the outside.

---

## Running it

Needs a JDK 21+. Nothing else — no Maven, no network, no BLAST.

```bash
make corpus          # generate the seeded database (once, ~1 min)
make test            # 54 tests against a real index over real sequence data
make bench           # reproduce the table above
make run             # http://localhost:9090
```

Or:

```bash
make corpus && docker compose up
```

### API

```bash
curl -X POST localhost:9090/search \
  -H 'Content-Type: application/json' \
  -d '{"sequence":"ACGTACGT..."}'
```

```json
{
  "elapsedMs": 1.802,
  "matches": [
    {
      "subjectId": "synthetic_0",
      "title": "synthetic virus genome 0, 151632 bp",
      "subjectLength": 151632,
      "alignmentScore": 261,
      "normalizedScore": 0.8700,
      "subjectOffset": 32,
      "seedHits": 192
    }
  ]
}
```

`normalizedScore` is the alignment score as a fraction of the best this query could achieve
against a perfect copy of itself, so it is comparable between queries of different lengths.
[v1's "percentage" was not](RETROSPECTIVE.md#8-a-percentage-that-could-not-be-compared-to-anything).

### Real data

The default corpus is synthetic and seeded, so the numbers above reproduce from a clean
clone in minutes. To run against NCBI's real viral genomes instead:

```bash
update_blastdb.pl --decompress ref_viruses_rep_genomes    # needs BLAST+
make bench ARGS="--db /path/to/ref_viruses_rep_genomes.fasta"
```

Both prefilters are behind one interface, so `blastn` remains available as a cross-check —
`KmerPrefilterTest` asserts the in-process index agrees with it on the top candidate for
every query. Agreeing with thirty years of NCBI's work is a stronger claim than agreeing
with expectations this repository wrote itself.

---

## Layout

```
src/main/java/vdrst/
  align/     scoring, encoding, and four Smith-Waterman implementations
  index/     genome store, k-mer index, diagonal accumulator, prefilters
  service/   validation and the two-stage search
  http/      JDK HttpServer, a small JSON writer, the page
src/test/    a ~100-line test runner and 54 tests
bench/       corpus generator and the staged benchmark
```

### Why no dependencies

v1 ran on Spring Boot 2.6.13 and carried iText, Apache POI, jsoup, org.json and a JWT
library — roughly forty jars, three with known CVEs by 2026 — to serve one endpoint that
mattered and several that [nothing could reach](RETROSPECTIVE.md#15-features-nobody-could-reach-defended-by-a-function-that-returned-true).

This is not an argument that Spring is the wrong tool. It is that a service with one route,
no persistence and no authentication was never the case for it. What is left is a Makefile,
`javac`, and the JDK: `make test` works offline on a laptop that has never seen this
project, and the Docker image needs no build stage because there is nothing to resolve.

---

## The 2024 version

Preserved, unedited, at [`v1.1-2024-complete`](../../tree/v1.1-2024-complete) — including
the complexity annotations I wrote at the time, which
[turned out to be wrong in an interesting way](RETROSPECTIVE.md#11-the-complexity-analysis-used-one-letter-for-two-things).
Two mechanical edits were applied to the history and nothing else: a live database password
was redacted, and `node_modules/` and build output were removed. No source logic was
altered.

[`v1.0-2024`](../../tree/v1.0-2024) is what was public at the time.
[`v1.1-2024-complete`](../../tree/v1.1-2024-complete) adds three classes that existed on my
machine in 2024 but were never pushed, so the starting point the retrospective measures
against is the code as it really stood.

---

## Credits

Front-end, back-end, algorithms and this rewrite: **Hu Jiawei (David)**.

The 2024 prototype design was by **Tang Bingni**. Domain guidance on viral genomics came
from **Tan Ke Qi**, **Teh Wen Xuan**, **Commettant Neil Jude** and **Huang ZhouXiang**, who
work in the field and were generous with their time.
