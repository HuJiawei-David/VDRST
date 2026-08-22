package vdrst.align;

import vdrst.harness.Assert;
import vdrst.harness.Test;

public final class ScoringSchemeTest {

    @Test("the blastn scheme matches the flags the prefilter is invoked with")
    public void blastnSchemeMatchesInvocation() {
        ScoringScheme s = ScoringScheme.prefilter();
        Assert.equal(1, s.match(), "-reward 1");
        Assert.equal(-2, s.mismatch(), "-penalty -2");
        Assert.equal(-2, s.gapOpen(), "-gapopen 2");
        Assert.equal(-2, s.gapExtend(), "-gapextend 2");
        Assert.isTrue(!s.isLinearGap(), "the prefilter uses affine gaps");
    }

    @Test("v1's own scheme was linear and disagreed with its own prefilter")
    public void legacySchemeWasInconsistent() {
        ScoringScheme legacy = ScoringScheme.legacyV1();
        ScoringScheme prefilter = ScoringScheme.prefilter();
        Assert.isTrue(legacy.isLinearGap(), "v1's Smith-Waterman charged a flat gap penalty");
        Assert.isTrue(legacy.match() != prefilter.match(),
                "v1 rewarded matches differently in each stage");
        Assert.isTrue(legacy.mismatch() != prefilter.mismatch(),
                "v1 penalised mismatches differently in each stage");
    }

    @Test("nonsensical schemes are rejected at construction")
    public void invalidSchemesRejected() {
        Assert.throwsException(IllegalArgumentException.class,
                () -> new ScoringScheme(0, -1, -1, -1), "match must be positive");
        Assert.throwsException(IllegalArgumentException.class,
                () -> new ScoringScheme(1, 1, -1, -1), "mismatch must be negative");
        Assert.throwsException(IllegalArgumentException.class,
                () -> new ScoringScheme(1, -1, -1, 0), "gap extension must cost something");
    }
}
