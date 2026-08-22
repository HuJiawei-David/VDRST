# VDRST — zero external dependencies. Everything here runs offline on a stock JDK 21.
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

test: $(TEST_OUT)
	@java $(VECTOR) --enable-native-access=ALL-UNNAMED \
		-cp $(OUT):$(TEST_OUT) vdrst.harness.TestRunner $(TESTS)

bench: build
	@mkdir -p $(BENCH_OUT)
	@javac $(JAVAC_FLAGS) $(VECTOR) -cp $(OUT) -d $(BENCH_OUT) $$(find $(BENCH_SRC) -name '*.java')
	@java $(VECTOR) $(BENCH_OPTS) -cp $(OUT):$(BENCH_OUT) vdrst.bench.BenchmarkMain $(ARGS)

corpus: build
	@mkdir -p $(BENCH_OUT)
	@javac $(JAVAC_FLAGS) -cp $(OUT) -d $(BENCH_OUT) $$(find $(BENCH_SRC) -name '*.java')
	@java -cp $(OUT):$(BENCH_OUT) vdrst.bench.CorpusGenerator $(ARGS)

run: build
	@java $(VECTOR) $(JAVA_OPTS) -cp $(OUT) vdrst.http.Main $(ARGS)

# Export a BLAST database to FASTA, which is what this project reads.
#   make fasta DB=/path/to/ref_viruses_rep_genomes
# Needed once, and only if your sequences came from update_blastdb.pl. Nothing else here
# uses BLAST.
fasta:
	@test -n "$(DB)" || (echo "usage: make fasta DB=/path/to/blast_database" && exit 1)
	blastdbcmd -db $(DB) -entry all -out $(DB).fasta
	@echo "wrote $(DB).fasta — start with: make run ARGS=\"--db $(DB).fasta\""

# Verify the toolchain before anything else fails confusingly.
doctor:
	@printf 'java    '; java -version 2>&1 | grep -v 'Picked up' | head -1 || echo 'MISSING'
	@printf 'javac   '; javac -version 2>&1 | grep -v 'Picked up' | head -1 || echo 'MISSING'
	@printf 'make    '; $(MAKE) --version 2>/dev/null | head -1 || echo 'MISSING'
	@javac --release $(JAVA_VERSION) -version >/dev/null 2>&1 \
		&& echo 'JDK $(JAVA_VERSION) target available — you are ready to run `make corpus`' \
		|| echo 'JDK $(JAVA_VERSION) NOT available — install a JDK 21 or newer (brew install openjdk@21)'
	@printf 'vectors '; java $(VECTOR) -cp $(OUT) -e 2>/dev/null; \
		echo '(reported at startup by `make run`)' 

clean:
	@rm -rf target
