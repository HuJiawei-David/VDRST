# VDRST — zero external dependencies. Everything here runs offline on a stock JDK 21.
#
#   make doctor    check the toolchain before anything fails confusingly
#   make test      build the test corpus if needed, then run the suite
#   make run       start the service on http://localhost:9090
#   make bench     reproduce the numbers in README.md
#
# BLAST+ is optional throughout. Nothing in VDRST uses it; it is only ever a cross-check
# for the in-process index, and everything degrades to "skipped" without it.

JAVA_VERSION := 21
SRC          := src/main/java
TEST_SRC     := src/test/java
BENCH_SRC    := bench/java
OUT          := target/classes
TEST_OUT     := target/test-classes
BENCH_OUT    := target/bench-classes
VECTOR       := --add-modules jdk.incubator.vector
JAVAC_FLAGS  := --release $(JAVA_VERSION) -Xlint:all -encoding UTF-8

# JVM flags for `run` and `bench`. A real viral database needs roughly 5 bytes per base,
# so a few hundred megabases wants something like JAVA_OPTS="-Xmx6g".
JAVA_OPTS    ?=
BENCH_OPTS   := $(if $(JAVA_OPTS),$(JAVA_OPTS),-Xms2g -Xmx2g)

# The small corpus the tests run against, and the larger one the benchmark uses.
CI_CORPUS    := bench/corpus-ci/viruses.fasta
BENCH_CORPUS := bench/corpus/viruses.fasta

TESTS := vdrst.align.AlignerEquivalenceTest \
         vdrst.align.BandedAlignerTest \
         vdrst.align.NucleotidesTest \
         vdrst.align.ScoringSchemeTest \
         vdrst.blast.BlastOutputParserTest \
         vdrst.blast.BlastRunnerTest \
         vdrst.index.KmerPrefilterTest \
         vdrst.service.SearchServiceTest \
         vdrst.service.ConcurrentSearchTest

.PHONY: all build test bench clean corpus run fasta doctor

all: test

build:
	@mkdir -p $(OUT)
	@javac $(JAVAC_FLAGS) $(VECTOR) -d $(OUT) $$(find $(SRC) -name '*.java')

$(TEST_OUT): build
	@mkdir -p $(TEST_OUT)
	@javac $(JAVAC_FLAGS) $(VECTOR) -cp $(OUT) -d $(TEST_OUT) $$(find $(TEST_SRC) -name '*.java')

$(BENCH_OUT): build
	@mkdir -p $(BENCH_OUT)
	@javac $(JAVAC_FLAGS) $(VECTOR) -cp $(OUT) -d $(BENCH_OUT) $$(find $(BENCH_SRC) -name '*.java')

# The tests need their own small corpus. Generating it here rather than asking the reader
# to remember a second command is the difference between `make test` working on a fresh
# clone and not.
$(CI_CORPUS): $(BENCH_OUT)
	@java -cp $(OUT):$(BENCH_OUT) vdrst.bench.CorpusGenerator --scale CI --out bench/corpus-ci

$(BENCH_CORPUS): $(BENCH_OUT)
	@java -cp $(OUT):$(BENCH_OUT) vdrst.bench.CorpusGenerator --scale DEFAULT --out bench/corpus

test: $(TEST_OUT) $(CI_CORPUS)
	@java $(VECTOR) -cp $(OUT):$(TEST_OUT) vdrst.harness.TestRunner $(TESTS)

bench: $(BENCH_OUT) $(BENCH_CORPUS)
	@java $(VECTOR) $(BENCH_OPTS) -cp $(OUT):$(BENCH_OUT) vdrst.bench.BenchmarkMain $(ARGS)

# make corpus                                  the benchmark corpus (500 genomes)
# make corpus ARGS="--scale CI --out somewhere" anything else
corpus: $(BENCH_OUT)
	@java -cp $(OUT):$(BENCH_OUT) vdrst.bench.CorpusGenerator $(ARGS)

run: build $(BENCH_CORPUS)
	@java $(VECTOR) $(JAVA_OPTS) -cp $(OUT) vdrst.http.Main $(ARGS)

# Export a BLAST database to FASTA, which is what this project reads.
#   make fasta DB=/path/to/ref_viruses_rep_genomes
# Needed once, and only if your sequences came from update_blastdb.pl.
fasta:
	@test -n "$(DB)" || (echo 'usage: make fasta DB=/path/to/blast_database' && exit 1)
	blastdbcmd -db $(DB) -entry all -out $(DB).fasta
	@echo 'wrote $(DB).fasta — start with: make run ARGS="--db $(DB).fasta"'

doctor:
	@printf 'java    '; java -version 2>&1 | grep -v 'Picked up' | head -1 || echo 'MISSING'
	@printf 'javac   '; javac -version 2>&1 | grep -v 'Picked up' | head -1 || echo 'MISSING'
	@printf 'make    '; $(MAKE) --version 2>/dev/null | head -1 || echo 'MISSING'
	@printf 'blastn  '; (blastn -version 2>/dev/null | head -1) || echo 'not installed (optional — only used to cross-check the index)'
	@javac --release $(JAVA_VERSION) -version >/dev/null 2>&1 \
		&& echo 'JDK $(JAVA_VERSION) target available — you are ready to run `make test`' \
		|| echo 'JDK $(JAVA_VERSION) NOT available — install a JDK 21 or newer (brew install openjdk@21)'

clean:
	@rm -rf target
