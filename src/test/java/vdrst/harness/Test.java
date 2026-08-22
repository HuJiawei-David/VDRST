package vdrst.harness;

import java.lang.annotation.*;

/** Marks a no-argument instance method as a test case. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Test {
    /** Human-readable description of the behaviour under test. */
    String value() default "";
}
