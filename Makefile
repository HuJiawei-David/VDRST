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

TESTS := vdrst.align.AlignerEquivalenceTest \
         vdrst.align.BandedAlignerTest \
         vdrst.align.NucleotidesTest \
         vdrst.align.ScoringSchemeTest \
         vdrst.blast.BlastOutputParserTest \
         vdrst.blast.BlastRunnerTest \
         vdrst.index.KmerPrefilterTest \
         vdrst.service.SearchServiceTest \
         vdrst.service.ConcurrentSearchTest

.PHONY: all build test bench clean corpus run

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
	@java $(VECTOR) -Xms2g -Xmx2g -cp $(OUT):$(BENCH_OUT) vdrst.bench.BenchmarkMain $(ARGS)

corpus: build
	@mkdir -p $(BENCH_OUT)
	@javac $(JAVAC_FLAGS) -cp $(OUT) -d $(BENCH_OUT) $$(find $(BENCH_SRC) -name '*.java')
	@java -cp $(OUT):$(BENCH_OUT) vdrst.bench.CorpusGenerator $(ARGS)

run: build
	@java $(VECTOR) -cp $(OUT) vdrst.http.Main $(ARGS)

clean:
	@rm -rf target
