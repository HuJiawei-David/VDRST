package vdrst.blast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs {@code blastn} as the candidate prefilter.
 *
 * <p>Everything about how v1 invoked the subprocess was wrong in a way that only shows
 * up under load, so all four defects are listed here rather than buried:
 *
 * <ol>
 *   <li><b>Shared mutable paths.</b> v1 wrote every query to {@code ./query.fasta} and
 *       every result to {@code ./result.txt}, both hard-coded. Two searches in flight at
 *       once overwrite each other's files, and a user can be handed the alignment for
 *       somebody else's sequence. v2 passes the query on stdin and reads results from
 *       stdout, so there is no shared filesystem state to race over — and two disk
 *       round-trips disappear with it.</li>
 *   <li><b>Undrained stderr.</b> v1 called {@code Runtime.exec} and then
 *       {@code waitFor()} without reading either output stream. Once blastn writes
 *       more than the OS pipe buffer holds (commonly 64 KiB) it blocks writing, while
 *       the caller blocks waiting for an exit that can never come. v2 drains stdout and
 *       stderr on their own threads, and writes stdin on a third, so no pipe can fill.</li>
 *   <li><b>No timeout.</b> {@code waitFor()} with no deadline means a wedged subprocess
 *       holds a request thread forever. v2 bounds every invocation and destroys the
 *       process when it overruns.</li>
 *   <li><b>Silent misconfiguration.</b> v1 discovered a missing database only when a
 *       search returned nothing. v2 fails at construction with the stderr blastn
 *       produced.</li>
 * </ol>
 *
 * <p>See RETROSPECTIVE.md findings 2, 3 and 7. Instances are immutable and safe to
 * share across threads; {@link #close()} shuts down the stream-pump pool.
 */
public final class BlastRunner implements AutoCloseable {

    /** How long a single blastn invocation may take before it is killed. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** Candidates handed to the re-ranking stage. v1 used 10, then 20. */
    public static final int DEFAULT_MAX_TARGETS = 20;

    private final String executable;
    private final String database;
    private final Duration timeout;
    private final int maxTargets;
    private final ExecutorService pumps;

    public BlastRunner(String executable, String database) {
        this(executable, database, DEFAULT_TIMEOUT, DEFAULT_MAX_TARGETS);
    }

    public BlastRunner(String executable, String database, Duration timeout, int maxTargets) {
        this.executable = java.util.Objects.requireNonNull(executable, "executable");
        this.database = java.util.Objects.requireNonNull(database, "database");
        this.timeout = java.util.Objects.requireNonNull(timeout, "timeout");
        if (maxTargets < 1) throw new IllegalArgumentException("maxTargets must be >= 1");
        this.maxTargets = maxTargets;

        AtomicLong counter = new AtomicLong();
        this.pumps = Executors.newCachedThreadPool(runnable -> {
            Thread t = new Thread(runnable, "blast-pump-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Thrown when blastn cannot be run, fails, or overruns its deadline. */
    public static final class BlastExecutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public BlastExecutionException(String message) { super(message); }
        public BlastExecutionException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Runs the prefilter over one query.
     *
     * @param sequence raw nucleotide characters, already validated by the caller
     * @return hits in the order blastn ranked them, empty when nothing matched
     */
    public List<BlastHit> search(String sequence) {
        String fasta = ">query\n" + sequence + "\n";
        Completed result = run(fasta);

        if (result.exitCode() != 0) {
            throw new BlastExecutionException(
                    "blastn exited " + result.exitCode() + ": " + firstLines(result.stderr(), 5));
        }
        return BlastOutputParser.parse(result.stdout());
    }

    /**
     * Verifies that the executable runs and the database is readable, so that a
     * misconfigured deployment fails at startup rather than on a user's first search.
     *
     * @throws BlastExecutionException describing what is wrong
     */
    public void verifyConfiguration() {
        Completed probe = run(">probe\nACGTACGTACGTACGTACGT\n");
        if (probe.exitCode() != 0) {
            throw new BlastExecutionException("blastn is not usable with database \"" + database
                    + "\": " + firstLines(probe.stderr(), 5));
        }
    }

    /**
     * Above this query length the {@code blastn-short} task stops being appropriate.
     * NCBI documents it as being for primers and probes; it lowers the word size and
     * disables the heuristics that make BLAST fast, which is affordable for 20 bases
     * and pathological for 20,000.
     */
    private static final int SHORT_QUERY_BASES = 50;

    private record Completed(int exitCode, String stdout, String stderr) {}

    private Completed run(String fastaInput) {
        // v1 hard-coded "-task blastn-short -word_size 4" for every query regardless of
        // length. That is the configuration NCBI intends for primer-sized input; applied
        // to a few hundred bases it merely wastes time, and applied to a long sequence it
        // does not finish. Picking the task from the query length is not a tuning choice,
        // it is using the tool as documented. See RETROSPECTIVE.md finding 10.
        boolean shortQuery = fastaInput.length() <= SHORT_QUERY_BASES + 16;
        String task = shortQuery ? "blastn-short" : "blastn";
        String wordSize = shortQuery ? "4" : "11";

        List<String> command = List.of(
                executable,
                "-db", database,
                "-outfmt", BlastOutputParser.OUTPUT_FORMAT,
                "-task", task,
                "-evalue", "100",
                "-word_size", wordSize,
                "-reward", "1",
                "-penalty", "-2",
                "-gapopen", "2",
                "-gapextend", "2",
                "-max_target_seqs", Integer.toString(maxTargets),
                "-max_hsps", "5");

        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (IOException e) {
            throw new BlastExecutionException(
                    "could not start \"" + executable + "\" — is BLAST+ installed and on PATH?", e);
        }

        // All three streams are pumped concurrently. Draining only one of them, or
        // writing stdin inline, reintroduces the deadlock described in the class docs.
        Future<?> stdin = pumps.submit(() -> {
            try (OutputStream out = process.getOutputStream()) {
                out.write(fastaInput.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // blastn closing the pipe early is not itself an error; the exit code decides.
            }
        });
        Future<String> stdout = pumps.submit(() -> drain(process.getInputStream()));
        Future<String> stderr = pumps.submit(() -> drain(process.getErrorStream()));

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                stdin.cancel(true);
                throw new BlastExecutionException(
                        "blastn exceeded its " + timeout.toSeconds() + "s deadline and was terminated");
            }
            stdin.get(5, TimeUnit.SECONDS);
            return new Completed(process.exitValue(),
                    stdout.get(5, TimeUnit.SECONDS),
                    stderr.get(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new BlastExecutionException("interrupted while waiting for blastn", e);
        } catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
            throw new BlastExecutionException("failed while reading blastn output", e);
        }
    }

    private static String drain(InputStream stream) {
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOExceptionWrapper(e);
        }
    }

    private static final class UncheckedIOExceptionWrapper extends RuntimeException {
        private static final long serialVersionUID = 1L;
        UncheckedIOExceptionWrapper(IOException cause) { super(cause); }
    }

    private static String firstLines(String text, int limit) {
        if (text == null || text.isBlank()) return "(no output on stderr)";
        List<String> kept = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            kept.add(line.trim());
            if (kept.size() == limit) break;
        }
        return String.join(" | ", kept);
    }

    public String database() { return database; }

    public int maxTargets() { return maxTargets; }

    @Override
    public void close() {
        pumps.shutdownNow();
    }
}
