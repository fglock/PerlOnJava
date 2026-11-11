package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class EndBoundaryPackageCodeRootTest {
    @Test
    void installedAnonymousClosureKeepsItsCapturedReferentRooted() {
        assertInstalledCodeCaptureRoot(
                "Issue1649AnonymousCodeSlot::installed",
                "__ANON__",
                "issue1649-anonymous-code-slot.pl");
    }

    @Test
    void evalDefinedNamedClosureKeepsItsCapturedReferentRooted() {
        assertInstalledCodeCaptureRoot(
                "Issue1649EvalCodeSlot::installed",
                "installed",
                "(eval 1649)");
    }

    @Test
    void detachedGlobCodeSlotIsRootedOnlyWhileAnAliasStillTargetsIt() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String identity = "\u0000glob-identity-issue1649-test";
            String alias = "Issue1649DetachedGlob::alias";
            RuntimeHash capturedReferent = new RuntimeHash();
            RuntimeScalar capturedScalar = capturedReferent.createAnonymousReference();
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.packageName = "Issue1649DetachedGlob";
            code.subName = "__ANON__";
            code.putClosedOverVariable("$captured", capturedScalar);
            RuntimeScalar codeRef = new RuntimeScalar(code);

            GlobalVariable.globalCodeRefs.put(identity, codeRef);
            GlobalVariable.globAliases.put(alias, identity);
            try {
                assertTrue(ReachabilityWalker.walkEndBlockRoots().contains(capturedReferent),
                        "a detached CODE slot stays live while an alias targets its identity");

                GlobalVariable.globAliases.put(alias, "Issue1649DetachedGlob::other");

                assertFalse(ReachabilityWalker.walkEndBlockRoots().contains(capturedReferent),
                        "an orphaned detached CODE slot must not retain its captures");
            } finally {
                GlobalVariable.globAliases.remove(alias);
                GlobalVariable.globalCodeRefs.remove(identity);
            }
        }
    }

    private static void assertInstalledCodeCaptureRoot(
            String slotName, String subName, String sourceFile) {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash capturedReferent = new RuntimeHash();
            RuntimeScalar capturedScalar = capturedReferent.createAnonymousReference();
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.packageName = slotName.substring(0, slotName.lastIndexOf("::"));
            code.subName = subName;
            code.cvStartFile = sourceFile;
            code.putClosedOverVariable("$captured", capturedScalar);
            RuntimeScalar codeRef = new RuntimeScalar(code);

            GlobalVariable.globalCodeRefs.put(slotName, codeRef);
            try {
                Set<RuntimeBase> roots = ReachabilityWalker
                        .walkEndBoundaryRootsExcludingRescued();
                Set<RuntimeBase> endBlockRoots = ReachabilityWalker.walkEndBlockRoots();

                assertTrue(roots.contains(codeRef), "the current package CODE slot is a root");
                assertTrue(roots.contains(capturedScalar),
                        "the package CV's semantic capture is a root");
                assertTrue(roots.contains(capturedReferent),
                        "the captured reference's referent remains live through END");
                assertTrue(endBlockRoots.contains(capturedReferent),
                        "the END callback walk must follow installed package CV captures");
            } finally {
                GlobalVariable.globalCodeRefs.remove(slotName);
            }
        }
    }
}
