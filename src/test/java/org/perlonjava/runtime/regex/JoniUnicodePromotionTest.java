package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.NamedCharacterExpansionMap;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;

@Tag("unit")
class JoniUnicodePromotionTest {
    private static final Method COMPILE_WITH_PROVENANCE = compileMethod();

    @Test
    void parserFactControlsEffectiveByteSemanticsAndVariants() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeRegex bytePattern = compile("\\x{ff}", true);
            assertTrue(booleanField(bytePattern, "sourcePatternByteBacked"));
            assertTrue(bytePattern.isPatternByteBacked());
            assertFalse(booleanField(bytePattern,
                    "unicodePromotingPatternSyntax"));
            assertTrue(bytePattern.recursivePatternBytes != null);

            RuntimeRegex promoted = compile("\\x{100}", true);
            assertTrue(booleanField(promoted, "sourcePatternByteBacked"));
            assertFalse(promoted.isPatternByteBacked());
            assertTrue(booleanField(promoted,
                    "unicodePromotingPatternSyntax"));
            assertNull(promoted.recursivePatternBytes);
            assertTrue(promoted.recursivePattern
                    .hasUnicodePromotingPatternSyntax());
            assertTrue(promoted.recursivePatternUnicode
                    .hasUnicodePromotingPatternSyntax());
        }
    }

    @Test
    void canonicalCacheRetainsSourceProvenanceAndResolvedFact() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeRegex first = compile("\\x{100}", true);
            assertSame(first, compile("\\x{100}", true));

            RuntimeRegex unicodeSource = compile("\\x{100}", false);
            assertFalse(first == unicodeSource,
                    "BYTE and UNICODE source requests must not alias");
            assertEquals(2, PerlRuntime.current().regexState
                    .compiledRegexCache.size());
        }
    }

    @Test
    void malformedPromotingSourceIsNeitherRetriedNorCached() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            int before = PerlRuntime.current().regexState
                    .compiledRegexCache.size();
            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> compile("\\x{100}(", true));
            assertTrue(error.getMessage().contains("Unmatched (")
                            || error.getMessage().contains(
                                    "unmatched parenthesis"),
                    error.getMessage());
            assertEquals(before, PerlRuntime.current().regexState
                    .compiledRegexCache.size());
        }
    }

    private static RuntimeRegex compile(String source, boolean byteBacked) {
        try {
            return (RuntimeRegex) COMPILE_WITH_PROVENANCE.invoke(null,
                    source, "", 0, 0, byteBacked, false, null, null);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Method compileMethod() {
        try {
            Method method = RuntimeRegex.class.getDeclaredMethod("compile",
                    String.class, String.class, int.class, int.class,
                    boolean.class, boolean.class, String.class,
                    NamedCharacterExpansionMap.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static boolean booleanField(RuntimeRegex regex, String name) {
        try {
            Field field = RuntimeRegex.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(regex);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
