package vdrst.harness;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * A ~100-line test runner, so that the project keeps its promise of zero external
 * dependencies. Test classes are passed by name; every {@code @Test}-annotated
 * instance method runs on a fresh instance.
 */
public final class TestRunner {

    private record Failure(String test, String message, Throwable cause) {}

    public static void main(String[] args) throws Exception {
        List<Failure> failures = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int passed = 0, total = 0;
        long started = System.nanoTime();

        for (String className : args) {
            Class<?> type = Class.forName(className);
            String shortName = type.getSimpleName();
            System.out.println("\n  " + shortName);

            for (Method method : type.getDeclaredMethods()) {
                Test annotation = method.getAnnotation(Test.class);
                if (annotation == null) continue;
                total++;

                String label = annotation.value().isEmpty() ? method.getName() : annotation.value();
                method.setAccessible(true);
                try {
                    Object instance = type.getDeclaredConstructor().newInstance();
                    method.invoke(instance);
                    passed++;
                    System.out.println("    ok   " + label);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Assert.TestSkipped) {
                        total--;
                        System.out.println("    skip " + label + "  (" + cause.getMessage() + ")");
                        skipped.add(shortName + "." + method.getName());
                    } else {
                        System.out.println("    FAIL " + label);
                        failures.add(new Failure(shortName + "." + method.getName(), cause.getMessage(), cause));
                    }
                }
            }
        }

        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println();
        if (!failures.isEmpty()) {
            System.out.println("  " + "-".repeat(66));
            for (Failure f : failures) {
                System.out.println("\n  FAILED  " + f.test());
                System.out.println("    " + String.valueOf(f.message()).replace("\n", "\n    "));
                if (!(f.cause() instanceof Assert.AssertionFailure)) {
                    for (StackTraceElement frame : f.cause().getStackTrace()) {
                        if (frame.getClassName().startsWith("vdrst")) {
                            System.out.println("    at " + frame);
                        }
                    }
                }
            }
            System.out.println();
        }

        System.out.printf("  %d/%d passed in %d ms%s%n%n", passed, total, millis,
                skipped.isEmpty() ? "" : " (" + skipped.size() + " skipped)");
        if (!skipped.isEmpty()) {
            System.out.println("  skipped because an optional tool is not installed:");
            for (String s : skipped) System.out.println("    " + s);
            System.out.println("  install BLAST+ to run them — nothing else in this project needs it\n");
        }
        if (!failures.isEmpty()) System.exit(1);
    }
}
