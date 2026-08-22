package vdrst.harness;

import java.util.Arrays;

/** Assertions. Throws {@link AssertionFailure}, which the runner reports without a stack trace. */
public final class Assert {

    public static final class AssertionFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        AssertionFailure(String message) { super(message); }
    }

    /**
     * Thrown when a test cannot run because something optional is not installed.
     *
     * <p>A test that needs BLAST+ is not failing when BLAST+ is absent — it is not
     * running, and reporting that as a failure trains people to ignore red output. The
     * distinction matters here because the whole project deliberately has no required
     * dependencies: everything BLAST-related is a cross-check, and a machine without it
     * should still get a clean, honest test run.
     */
    public static final class TestSkipped extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public TestSkipped(String reason) { super(reason); }
    }

    /** Abandons the current test, reporting it as skipped rather than failed. */
    public static void skip(String reason) {
        throw new TestSkipped(reason);
    }

    private Assert() {}

    public static void isTrue(boolean condition, String message) {
        if (!condition) throw new AssertionFailure(message);
    }

    public static void equal(int expected, int actual, String message) {
        if (expected != actual)
            throw new AssertionFailure(message + " — expected " + expected + ", got " + actual);
    }

    public static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionFailure(message + "\n  expected: " + expected + "\n  actual:   " + actual);
    }

    public static void equal(byte[] expected, byte[] actual, String message) {
        if (!Arrays.equals(expected, actual))
            throw new AssertionFailure(message
                    + "\n  expected: " + Arrays.toString(expected)
                    + "\n  actual:   " + Arrays.toString(actual));
    }

    public static void contains(String haystack, String needle, String message) {
        if (haystack == null || !haystack.contains(needle))
            throw new AssertionFailure(message + " — expected to contain \"" + needle + "\", got: " + haystack);
    }

    /** Asserts that {@code body} throws {@code expected}, and returns the thrown instance. */
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> T throwsException(Class<T> expected, Runnable body, String message) {
        try {
            body.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) return (T) actual;
            throw new AssertionFailure(message + " — expected " + expected.getSimpleName()
                    + " but got " + actual.getClass().getSimpleName() + ": " + actual.getMessage());
        }
        throw new AssertionFailure(message + " — expected " + expected.getSimpleName() + ", but nothing was thrown");
    }
}
