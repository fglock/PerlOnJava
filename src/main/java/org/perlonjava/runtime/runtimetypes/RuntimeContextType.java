package org.perlonjava.runtime.runtimetypes;

/**
 * The RuntimeContextType enum defines the various types of contexts that can be encountered
 * in the Perl programming language. Perl has a unique context system that determines
 * how expressions are evaluated and what kind of values are expected or returned.
 *
 * <p>This enum is used to categorize and manage the different contexts in Perl,
 * allowing for more organized and efficient handling of various scenarios within
 * a Perl interpreter or compiler.</p>
 */
public class RuntimeContextType {
    /**
     * Represents a void context in Perl, where no value is expected or returned.
     * This is typically used for functions or operations that do not produce a result.
     * In Perl, this is often seen in statements that are executed for their side effects.
     */
    public static final int VOID = 0;

    /**
     * Represents a scalar context in Perl, where a single scalar value is expected
     * or returned. Scalar values in Perl include numbers, strings, and references.
     * This context is used for operations that produce or operate on individual data items.
     */
    public static final int SCALAR = 1;

    /**
     * Represents a list context in Perl, where a list of values is expected or returned.
     * This context is used for operations that produce or operate on collections of items,
     * such as arrays or lists. In Perl, list context can affect how functions and operators
     * behave and what they return.
     */
    public static final int LIST = 2;

    /**
     * Represents a runtime context in Perl, where the context will be decided at runtime.
     * This means that the context is not known at compile-time and can vary depending on
     * where and how a subroutine or expression is called. For example, a subroutine can be
     * called from different places in different contexts, and the actual context will be
     * determined during the execution of the program.
     */
    public static final int RUNTIME = 3;

    /**
     * Represents scalar lvalue context for subroutine and method calls used on
     * the left-hand side of assignment. The callee still executes in scalar
     * context, but the runtime verifies that the called code has the :lvalue
     * attribute before allowing assignment through its return value.
     */
    public static final int LVALUE = 4;

    /**
     * Represents list lvalue context for subroutine and method calls used as
     * list-assignment targets. The callee sees list context for wantarray(),
     * while the runtime verifies the :lvalue attribute and preserves returned
     * aliases instead of copying list values.
     */
    public static final int LVALUE_LIST = 5;

    public static boolean isListLike(int context) {
        return context == LIST || context == LVALUE_LIST;
    }
}
