# Retrospective

I wrote VDRST between September and November 2024. It was my first real project. In 2026
I came back to it, and this is what I found.

The code is preserved, unedited, at [`v1.1-2024-complete`](../../tree/v1.1-2024-complete).
Everything below can be checked against it.

I have tried to write this the way I would want a code review written for me: specific
about what is wrong, specific about why someone would write it that way, and honest about
which problems I understood at the time and which I simply did not know existed.

---

## Contents

**Things that were broken**
1. [A live database password, public for two years](#1-a-live-database-password-public-for-two-years)
2. [Two people searching at once got each other's results](#2-two-people-searching-at-once-got-each-others-results)
3. [Any input at all was accepted](#3-any-input-at-all-was-accepted)
4. [A subprocess that could hang forever](#4-a-subprocess-that-could-hang-forever)

**Things that were subtly wrong**

5. [The two halves of the search disagreed about "similar"](#5-the-two-halves-of-the-search-disagreed-about-similar)
6. [A gap model that contradicted its own configuration](#6-a-gap-model-that-contradicted-its-own-configuration)
7. [Alignments across junctions that do not exist](#7-alignments-across-junctions-that-do-not-exist)
8. [A percentage that could not be compared to anything](#8-a-percentage-that-could-not-be-compared-to-anything)
9. [Parsing output NCBI does not promise to keep stable](#9-parsing-output-ncbi-does-not-promise-to-keep-stable)
10. [Using blastn-short for sequences it was never meant for](#10-using-blastn-short-for-sequences-it-was-never-meant-for)

**Things I got wrong about my own work**

11. [The complexity analysis used one letter for two things](#11-the-complexity-analysis-used-one-letter-for-two-things)
12. [I recorded a result and not a method](#12-i-recorded-a-result-and-not-a-method)
13. [I optimised the wrong thing, and only measurement caught it](#13-i-optimised-the-wrong-thing-and-only-measurement-caught-it)

**Things that were simply untidy**

14. [3,450 files that should never have been committed](#14-3450-files-that-should-never-have-been-committed)
15. [Features nobody could reach, defended by a function that returned true](#15-features-nobody-could-reach-defended-by-a-function-that-returned-true)

---

# Things that were broken

## 1. A live database password, public for two years

**What I wrote.** `SpringBoot_1/src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:mysql://${ip}:3306/honey2024
    username: root
    password: <a real password, in plain text, in a public repository>
```

Committed in December 2024 to a public repository, and present in 82 places across the
history — the compiled copy under `target/classes/` was committed too.

**Why I wrote it that way.** The application would not start without a datasource, the
value was right there in my IDE, and the repository felt like a place I put my code rather
than a place the internet reads. I did not think of a config file as something that gets
published.

**Why it is wrong.** It is not a style problem. The credential was disclosed the moment
the repository went public and stayed disclosed for two years. Rotating it is the fix;
deleting the line only stops it getting worse. Public repositories are scraped
continuously for exactly this pattern.

**What I did.** Rotated the credential first — that is the part that actually matters, and
no amount of git surgery substitutes for it. Then `git filter-repo --replace-text` over the
whole history. That rewrite preserves every commit message, author and timestamp; only the
string changed. And a `.gitignore`, which v1 did not have at all, which is the root cause
of this and of [finding 14](#14-3450-files-that-should-never-have-been-committed).

---

## 2. Two people searching at once got each other's results

**What I wrote.** In `VirusSearchService.search`:

```java
String queryFile = "./query.fasta";
// ...
try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(queryFile))) {
    writer.write(">UserInput\n");
    writer.write(sequence.trim());
}
```

and the output path came from `blast.output.file: ./result.txt`, a single configuration
value used by every request.

**Why I wrote it that way.** Because it worked. I tested by opening the page, pasting a
sequence, and pressing the button — one request at a time, always. The words "at the same
time" never came up, because in my testing there was never a second person.

**Why it is wrong.** Two fixed paths shared by every request. Request A writes its query;
request B overwrites it before A's subprocess reads it; A runs BLAST against B's sequence
and returns the result to A's user. This is not a rare interleaving — it is what happens
whenever two searches overlap at all, and a search took about fifty milliseconds.

This is the one that bothers me most. Not because it is the hardest bug in the list, but
because the service returns a *confident, well-formatted, completely wrong answer*, and
nothing anywhere would have told anybody. A crash gets noticed. This does not.

**What I did.** The query now goes to the subprocess on stdin and results come back on
stdout, so there is no path to collide over — the bug cannot recur, rather than being
carefully avoided. Two disk round-trips disappeared with it. (The subprocess itself is
gone now too, for reasons in [finding 13](#13-i-optimised-the-wrong-thing-and-only-measurement-caught-it),
but that came later and for different reasons.)

`ConcurrentSearchTest` guards it from both sides. One test shows v1's design handed every
request the same two paths — a deterministic assertion, not a race that has to be caught
in the act. The other drives 32 concurrent searches through v2 and checks every single one
came back with the answer to its own question.

---

## 3. Any input at all was accepted

**What I wrote.** Nothing. There was no validation. The request body's `sequence` field
was trimmed and written into a FASTA file that was handed to a subprocess.

**Why I wrote it that way.** I was thinking about the case where the input is a DNA
sequence, because that is what the form is for.

**Why it is wrong.** "What the form is for" describes the users you imagined, not the
requests that arrive. An empty string, a 100 MB paste, a control character, a shell
metacharacter — all accepted and passed downstream. The command was assembled as a
`String[]`, so `Runtime.exec` did not hand it to a shell, and I want to be accurate about
that: this was not a command injection. But the absence of injection was luck about which
`exec` overload I happened to use, not a decision I made.

**What I did.** `Nucleotides.encode` rejects anything outside A/C/G/T/U/N and reports the
offending character and its position. `SequenceValidator` bounds the length at both ends
and strips FASTA headers instead of rejecting them, because pasting a FASTA record is what
people actually do. Validation runs before any other work starts.

---

## 4. A subprocess that could hang forever

**What I wrote.**

```java
Process process = Runtime.getRuntime().exec(cmd);
int exitCode = process.waitFor();
```

**Why I wrote it that way.** This is the two-line form that appears in every tutorial and
in the answer to every "how do I run a command from Java" question. It works when the
subprocess prints little and exits quickly.

**Why it is wrong.** Two separate faults.

*The pipes are never drained.* A child process writes to a pipe with a buffer of, typically,
64 KiB. Once it is full the child blocks writing. The parent, meanwhile, is inside
`waitFor()`, waiting for an exit that cannot happen because the child is blocked. Neither
side moves again. Nothing times out; the request thread is simply gone until the JVM
restarts. I did not know this was a thing that could happen.

*There is no deadline.* `waitFor()` with no argument waits forever. Any subprocess that
wedges takes a request thread with it, permanently.

**What I did.** `ProcessBuilder`, with stdin, stdout and stderr each pumped on their own
thread so no pipe can fill, and `waitFor(timeout, unit)` followed by `destroyForcibly()`.
`BlastRunnerTest` covers both: a 90 KB query, comfortably past the pipe buffer, and an
invocation given a one-millisecond deadline that must terminate rather than hang.

While writing the second of those tests I found [finding 10](#10-using-blastn-short-for-sequences-it-was-never-meant-for),
which turned out to matter more.

---

# Things that were subtly wrong

## 5. The two halves of the search disagreed about "similar"

**What I wrote.** The prefilter was invoked with:

```java
"-reward", "1", "-penalty", "-2", "-gapopen", "2", "-gapextend", "2",
```

and the Smith-Waterman that re-ranked its output began:

```java
int matchScore = 2;
int mismatchScore = -1;
int gapPenalty = -1;
```

**Why I wrote it that way.** I chose the BLAST flags by reading NCBI's documentation about
short-sequence searches. I chose the Smith-Waterman constants from a textbook example of
Smith-Waterman. Each set was reasonable where it came from. It did not occur to me that
they had to be the *same* set, because I was thinking of the two stages as two tasks rather
than as one question asked twice.

**Why it is wrong.** The pipeline is: BLAST decides which sequences are worth a closer
look, then Smith-Waterman decides which of those is best. If the two use different scoring,
the stage selecting the candidates and the stage ranking them are optimising different
objectives. BLAST could hand over the right sequence and my re-ranking could push it down
the list, or promote a worse candidate — and both stages would be working perfectly by
their own definitions.

**What I did.** `ScoringScheme` is now a value that both stages take, and
`ScoringScheme.prefilter()` is the single definition. `ScoringSchemeTest` asserts the
scheme matches the flags the prefilter is actually invoked with, so the two cannot drift
apart again without a test failing.

---

## 6. A gap model that contradicted its own configuration

**What I wrote.** A single flat penalty, charged per gap position:

```java
int gapPenalty = -1;
// ...
int scoreDel = H[i - 1][j] + gapPenalty;
int scoreIns = H[i][j - 1] + gapPenalty;
```

**Why I wrote it that way.** This is the Smith-Waterman in the textbook. Affine gaps are a
paragraph further on, usually presented as a refinement.

**Why it is wrong.** Biologically, one deletion of four bases is a single event. Charging
it four times says it is four independent events, which makes the algorithm prefer
scattered single mismatches over one clean indel — the opposite of what viral variation
actually looks like.

And I already knew this, in a sense: `-gapopen 2 -gapextend 2` in my own BLAST invocation
*is* an affine model. I passed BLAST a gap-open cost and a separate gap-extend cost, then
wrote a Smith-Waterman with no notion of opening a gap. I had the right idea in one file
and not in the file next to it.

**What I did.** `GotohAligner` implements affine gaps (Gotoh 1982), tracking three
recurrences instead of one. It is also a space improvement: only the previous row is kept,
so space goes from O(mn) to O(min(m, n)). `LegacySmithWaterman` preserves v1's algorithm so
the benchmarks have an honest baseline and the claims here can be re-checked rather than
believed.

---

## 7. Alignments across junctions that do not exist

**What I wrote.**

```java
StringBuilder combinedQuery = new StringBuilder();
StringBuilder combinedSubject = new StringBuilder();
for (HSP hsp : candidate.hsps) {
    combinedQuery.append(hsp.querySeq);
    combinedSubject.append(hsp.sbjctSeq);
}
int rawScore = runSmithWaterman(combinedQuery.toString(), combinedSubject.toString());
```

**Why I wrote it that way.** BLAST returns several aligned segments per hit and I needed
one score per hit. Concatenating them looked like the natural way to consider all the
evidence at once.

**Why it is wrong.** Those segments come from different regions of the subject, often
thousands of bases apart. Joining them end to end creates a sequence that exists nowhere,
and then finds alignments spanning the seams — alignments across a junction that is an
artefact of my own string concatenation.

**What I did.** Each HSP is scored on its own and the best one represents the hit. Reading
`sseq` and `qseq` out of tabular output, rather than scraping them from the pairwise
report, is what makes that straightforward — see [finding 9](#9-parsing-output-ncbi-does-not-promise-to-keep-stable).

---

## 8. A percentage that could not be compared to anything

**What I wrote.**

```java
int minLen = Math.min(combinedQuery.length(), combinedSubject.length());
int maxPossibleScore = minLen * 2;
int percentageScore = (int)(((double) rawScore / maxPossibleScore) * 100);
```

**Why I wrote it that way.** The interface needed a number a person could read, and a
percentage is the most legible number there is.

**Why it is wrong.** Three things, compounding.

- `minLen` is measured on the concatenated strings, which still contain `-` gap
  characters. Gaps are counted as sequence.
- The `2` hard-codes a match reward of 2, while the prefilter that produced the candidate
  used a reward of 1. It is the [finding 5](#5-the-two-halves-of-the-search-disagreed-about-similar)
  inconsistency, showing up a second time in a place I did not expect it.
- Most importantly, the denominator depends on the alignment, which depends on the
  sequences. Two queries with the same displayed "87%" are not equally good matches, and
  the number cannot be compared across searches — which is the only thing a user would
  ever want to do with it.

A number that is legible and meaningless is worse than no number. It invites a decision.

**What I did.** Three quantities with definitions, in `Match`: the raw alignment score; a
normalised score in [0, 1] against the best that query could achieve against a perfect copy
of itself, which *is* comparable across queries; and the count of seed hits the prefilter
found, which says how much evidence there was to begin with.

---

## 9. Parsing output NCBI does not promise to keep stable

**What I wrote.** About 150 lines scanning BLAST's pairwise report for lines beginning
`Query`, `Sbjct` and `Score =`, splitting on whitespace, and tracking state across them
with a fistful of `lastQueryLine` / `lastSbjctSeq` variables.

**Why I wrote it that way.** It is the output I could see in the terminal. It looked
structured, so I structured a parser around it.

**Why it is wrong.** That format is for humans, and NCBI does not guarantee it across
releases — the tabular formats are the machine-readable ones. My parser was also lossy in
ways I did not notice: several branches only saved an HSP if the *previous* one had been
complete, so HSPs were dropped silently depending on how the blocks happened to be
ordered.

**What I did.** `-outfmt 6` with an explicit column list. The parser is now a `split("\t")`
and a group-by, it rejects malformed rows loudly instead of skipping them, and it names
the offending column when a field will not parse. About 150 lines of string handling
became about 40 of straightforward code.

---

## 10. Using blastn-short for sequences it was never meant for

**What I wrote.**

```java
"-task", "blastn-short",
"-word_size", "4",
```

for every query, regardless of length.

**Why I wrote it that way.** My test sequences were short, `blastn-short` sounded like it
was for short sequences, and a smaller word size finds more — I had left myself the comment
`//High matching probability`. It found what I expected it to find, so I stopped thinking
about it.

**Why it is wrong.** `blastn-short` is NCBI's configuration for primers and probes, under
about 50 bases. It lowers the word size and disables the heuristics that make BLAST fast,
which is a fine trade for 20 bases and a catastrophic one for 20,000. It is not that I
tuned it badly. I used the tool in a mode it documents itself as not being for.

I found this while writing a test for [finding 4](#4-a-subprocess-that-could-hang-forever).
I wrote a 90 KB query to prove the pipe-drain fix worked, and the test failed — not on a
deadlock, but because the search genuinely did not finish inside sixty seconds.

**What I did.** The task is selected from the query length. The whole integration suite
went from **88.8 s to 2.5 s**.

That is a 35x speedup that came from reading the documentation for a flag I had already
written, and it is worth more than any optimisation I made deliberately.

---

# Things I got wrong about my own work

## 11. The complexity analysis used one letter for two things

**What I wrote.** Comments scattered through `VirusSearchService`: `//O(S + M * N)` above
the class, `//O (s * M * N)` and `//O (m * n)` around the Smith-Waterman, `// m^2` on a
loop. My CV said the work took the search "from O(N×M×N) to O(N+M×N)".

**What I was actually thinking.** N sequences in the database, each of length N, a query of
length M: for each of N sequences, run an O(M×N) alignment. That reasoning is correct. The
prefilter reduces the sequence count to a constant, so the second stage stops scaling with
the database.

**Why it is wrong as written.** `N` is doing two different jobs in one expression — the
number of sequences and the length of each. The reasoning survives; the notation does not.
The first question anyone would ask is whether those two Ns are the same quantity, and I
would not have had a clean answer.

There is also something the notation hid from me. My Smith-Waterman never ran against whole
genomes:

```java
for (HSP hsp : candidate.hsps) { combinedQuery.append(hsp.querySeq); ... }
int rawScore = runSmithWaterman(combinedQuery.toString(), ...);
```

The input is BLAST's HSP fragments — a few hundred bases — not the genome they came from,
which may be hundreds of thousands. So the second stage was always far cheaper than my own
analysis said, and my speedup depended on the prefilter even more completely than I
claimed. I had written down a model of a program I had not actually written.

**The analysis, restated.** With G genomes averaging L bases, a query of length Q, and K
candidates surviving the prefilter:

| | |
|---|---|
| No prefilter | O(G · Q · L̄) |
| With prefilter | O(prefilter) + O(K · Q · H), K constant, H = aligned length |

Measured, on the reference corpus: G = 500, L̄ ≈ 47,000, Q = 300, K = 20.

The B0 → B1 row of the benchmark is that first line becoming the second: **25,302 ms to
47.6 ms, 531x**. My 2024 instinct — that nearly all of the speedup came from cutting the
candidate set, not from anything clever in the alignment — was right. I could not have
defended it with the notation I used to write it down.

---

## 12. I recorded a result and not a method

**What I wrote.** On my CV: search "48s to <500ms, ~96x".

**What I remember.** Both numbers came from my own machine in 2024, and I believe them.

**Why that is not enough.** I wrote down two numbers and none of the things that make a
number mean something: which machine, which database, which query, how many runs, whether
that was a median or the fastest one or the one I happened to be looking at. There was no
code in the repository that produced the 48 seconds — the full-database scan was never
committed, because by the time I was measuring I had already replaced it. The denominator
of my headline claim did not exist anywhere.

So the claim is unfalsifiable, which is a strange thing for a claim to be when it is also
true.

**What I did.** `bench/` builds the baseline as running code, and the corpus generator
produces a fixed-seed database so anyone can reproduce the whole table from a clean clone
without downloading anything. Percentiles, not averages — the mean of a run that is fast
most of the time and pathological occasionally looks fine, and the occasionally is the part
that matters. Environment, corpus and iteration counts are printed above every table.

I did not try to reproduce 48 seconds. That measurement belongs to a machine and a database
I no longer have, and dressing up a different number as the same one would be worse than
admitting it is gone. The benchmark stands on its own and says what it measured.

---

## 13. I optimised the wrong thing, and only measurement caught it

This is not a defect in v1. It is the mistake I was about to make in v2, and it is the
reason the rest of the work looks the way it does.

**The plan.** Alignment is the expensive part of sequence search — that is why fast
Smith-Waterman implementations are a research area. So: rolling buffers, then a band, then
SIMD. Everyone knows the DP matrix is where the time goes.

**The measurement.** Before writing any of it, I split the latency:

```
blast subprocess         49.89 ms   99.2%
smith-waterman re-rank    0.41 ms    0.8%
```

Smith-Waterman was 0.8% of a search. Making it infinitely fast would have improved end-to-end
latency by under one percent. So I broke the other 99% down:

```
/bin/true (bare process fork)          2.49 ms
blastn -version (fork + libraries)    31.21 ms
blastn, 20-base query (+ db open)     40.22 ms
blastn, 300-base query (real work)    39.87 ms
```

The last two lines are the ones to read. A trivial query and a real one cost the same,
because **the similarity search does not appear in its own latency budget.** About 29
milliseconds was the operating system loading the blastn binary and its shared libraries;
about 9 more was reopening the database. Roughly 40 ms of identical setup, repeated on
every request, to do work that did not register.

**What I did.** Replaced the subprocess with an in-process k-mer index — direct-addressed
buckets in CSR layout, seed hits collected by diagonal, per-thread scratch reused across
queries, and a hard cap on work per query so the tail stays predictable. The prefilter went
from 49.89 ms to **0.135 ms**.

That inverted the budget. With the process launch gone, alignment became 86% of a search —
and *only then* were rolling buffers, banding and SIMD worth writing. They were the right
optimisations applied at the wrong time; measuring told me when.

The vectorised aligner is 5.75x the scalar one, and its first version was only 1.75x
because I had left an O(mn) scalar copy inside the loop, which the same benchmark caught.

**What I take from it.** I would have written the SIMD Smith-Waterman first. It is the
interesting part, it is the part that looks like optimisation, and it would have made the
service imperceptibly faster while I told myself I had made it four times faster. The thing
that stopped me was not knowing more about alignment. It was spending an afternoon
measuring before spending a week coding.

---

# Things that were simply untidy

## 14. 3,450 files that should never have been committed

`node_modules/` (3,450 files), `SpringBoot_1/target/` including compiled `.class` files and
a second copy of the password-bearing config, `.idea/`, four `.iml` files, eleven
`.DS_Store`s, six JPEGs somebody had uploaded through the file endpoint, and `query.fasta`
and `result.txt` — the working files [finding 2](#2-two-people-searching-at-once-got-each-others-results)
is about, committed with whatever the last search had left in them.

Of 3,538 tracked files, 25 were source.

The cause is simple: there was no `.gitignore`. The same omission put the database password
in the repository twice.

After the history rewrite: **7.42 MiB to 18.9 KiB, 3,538 files to 25.**

---

## 15. Features nobody could reach, defended by a function that returned true

`FileController` served files by path:

```java
File file = new File(downloadDir + "/" + fileName);
```

with `fileName` taken straight from the URL. That is a directory traversal:
`../../../../etc/passwd` resolves fine. In front of it:

```java
private boolean validateToken(String token) {
    return true; // 示例：始终返回 true
}
```

The same function guarded `ResultsController`, which generated PDFs and Word documents
through iText 5.5.8 and Apache POI 5.2.3 — both carrying known CVEs by 2026.

`UserService` compared passwords as plain text and reset them to the literal string `"123"`
after checking a phone number.

None of it was reachable. The Vue router registered exactly three routes — `/`, `/results`,
and a 404 — with no login page and no navigation guard. `Person.vue`, the only view that
called the user endpoints, was never registered at all. This was attack surface with no
users, kept alive because deleting code felt like losing work.

**What I did.** Deleted all of it. The search path never touched the database, so removing
MySQL removed the last runtime dependency it had. v2 needs a JDK.

---

# What I would tell 2024 me

Not "learn about concurrency" or "read about affine gaps". Those followed on their own once
the habits were there.

**Test the thing you are afraid of, not the thing you just built.** Every test I wrote in
2024 confirmed something worked. None of them tried to break anything. The concurrency bug
needed one test with two threads in it, and I would have found it the same afternoon.

**Measure before optimising, then measure again after.** I would have spent a week
vectorising Smith-Waterman to improve latency by 0.8%. An afternoon with a timer pointed at
the actual answer, and the same benchmark later caught my SIMD implementation leaving two
thirds of its speedup on the floor.

**Write down how, not just what.** "48 seconds to 500 milliseconds" is a fact I can no
longer support. The same afternoon spent recording the method would have made it a fact I
could hand to anyone.

**Read the documentation for the flags you already wrote.** The largest single speedup in
this entire rewrite — 35x on the test suite — came from noticing that `-task blastn-short`
means something specific, in a line I had written myself two years earlier and never
questioned.

The two-stage design was right. The idea of using a cheap filter to make an expensive exact
algorithm affordable is still the whole point of the project, and 2024 me got there without
being told. Everything in this document is about the difference between having a good idea
and building something that can be trusted.
