package org.perlonjava;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.ErrorMessageUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link ErrorMessageUtil#stringifyException} produces user-friendly
 * output when Java exceptions leak through to users, particularly for chained
 * or wrapped exceptions where raw Java toString() would otherwise surface.
 */
@Tag("unit")
public class ErrorMessageUtilTest {

    @Test
    void stripJavaExceptionPrefix_stripsFullyQualifiedJavaException() {
        assertEquals("Cannot invoke null.x()",
                ErrorMessageUtil.stripJavaExceptionPrefix(
                        "java.lang.NullPointerException: Cannot invoke null.x()"));
        assertEquals("some message",
                ErrorMessageUtil.stripJavaExceptionPrefix(
                        "org.perlonjava.runtime.RuntimeException: some message"));
    }

    @Test
    void stripJavaExceptionPrefix_leavesPerlMessagesAlone() {
        // Looks like "Prefix: message" but isn't a fully-qualified Java class
        assertEquals("Foo: not a java class",
                ErrorMessageUtil.stripJavaExceptionPrefix("Foo: not a java class"));
        // Perl-style error message
        String perlMsg = "Undefined subroutine &main::foo called at -e line 1.";
        assertEquals(perlMsg, ErrorMessageUtil.stripJavaExceptionPrefix(perlMsg));
        // Single-identifier package with colon — not a Java exception
        assertEquals("DBI error: handle closed",
                ErrorMessageUtil.stripJavaExceptionPrefix("DBI error: handle closed"));
    }

    @Test
    void stringifyException_suppressesWrappingToString() {
        // A common accidental pattern: RuntimeException(inner.toString(), inner)
        Throwable inner = new NoClassDefFoundError("org/perlonjava/runtime/operators/KillOperator");
        Throwable outer = new RuntimeException(inner.toString(), inner);
        String out = ErrorMessageUtil.stringifyException(outer);
        // The "java.lang.NoClassDefFoundError:" line should NOT appear — it's
        // redundant with the inner cause's message.
        assertFalse(out.contains("java.lang.NoClassDefFoundError:"),
                "stringifyException should not surface wrapping Java toString(): " + out);
        assertTrue(out.contains("org/perlonjava/runtime/operators/KillOperator"),
                "inner message should still appear: " + out);
    }

    @Test
    void stringifyException_collapsesSlashedAndDottedDuplicates() {
        // NoClassDefFoundError typically uses slashes, ClassNotFoundException dots —
        // users would see both lines for the same class name. Suppress the redundant one.
        Throwable cnf = new ClassNotFoundException("org.perlonjava.runtime.operators.KillOperator");
        Throwable ncdfe = new NoClassDefFoundError("org/perlonjava/runtime/operators/KillOperator");
        ncdfe.initCause(cnf);

        String out = ErrorMessageUtil.stringifyException(ncdfe);
        // Should not contain BOTH the slashed and dotted forms — only one reading.
        assertFalse(out.contains("org/perlonjava/runtime/operators/KillOperator") &&
                    out.contains("org.perlonjava.runtime.operators.KillOperator"),
                "slashed and dotted class names should not both appear: " + out);
        // But SOME form of the class name should be present.
        assertTrue(out.contains("KillOperator"), "class name should be present: " + out);
    }

    @Test
    void stringifyException_plainPerlMessagePassesThrough() {
        // Messages ending with \n are standard Perl die strings; they should be
        // preserved exactly (Perl suppresses stack-trace appendage in this case).
        Throwable t = new RuntimeException("Undefined subroutine &main::foo called at -e line 1.\n");
        String out = ErrorMessageUtil.stringifyException(t);
        assertEquals("Undefined subroutine &main::foo called at -e line 1.\n", out);
    }
}
