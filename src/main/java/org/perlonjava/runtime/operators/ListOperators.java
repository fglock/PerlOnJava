package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.*;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

public class ListOperators {
    /**
     * Eagerly release captured variable references from an ephemeral grep/map/all/any
     * block closure. Like eval BLOCK closures, these blocks execute and are immediately
     * discarded. Without this, captureCount stays elevated on captured variables,
     * preventing scopeExitCleanup from decrementing blessed ref refCounts — causing
     * objects to never reach refCount 0 and DESTROY to never fire.
     * <p>
     * Only releases captures for closures flagged as isMapGrepBlock (set by the
     * compiler for BLOCK syntax). Named subs and user closures are not affected.
     */
    private static void releaseEphemeralCaptures(RuntimeScalar closure) {
        if (closure.type == RuntimeScalarType.CODE
                && closure.value instanceof RuntimeCode code
                && code.isMapGrepBlock) {
            code.releaseCaptures();
        }
    }

    /**
     * Transforms the elements of this RuntimeArray using a Perl subroutine.
     * This version passes the outer @_ to the map block for Perl compatibility.
     *
     * @param runtimeList
     * @param perlMapClosure A RuntimeScalar representing the Perl map subroutine.
     * @param outerArgs The @_ from the surrounding subroutine (can be null for top-level code)
     * @param ctx The calling context
     * @return A new RuntimeList with the transformed elements.
     * @throws RuntimeException If the Perl map subroutine throws an exception.
     */
    public static RuntimeList map(RuntimeList runtimeList, RuntimeScalar perlMapClosure, RuntimeArray outerArgs, int ctx) {

        // Create a new list to hold the transformed elements
        List<RuntimeBase> transformedElements = new ArrayList<>();

        RuntimeScalar saveValue = getGlobalVariable("main::_");

        try {
            // Use the outer @_ instead of an empty array
            // This allows $_[0], $_[1], etc. to work inside map blocks
            RuntimeArray mapArgs = outerArgs != null ? outerArgs : new RuntimeArray();

            // Iterate over each element in the current RuntimeArray
            for (RuntimeScalar element : runtimeList) {
                // Create $_ argument for the map subroutine
                GlobalVariable.aliasGlobalVariable("main::_", element);

                // Apply the Perl map subroutine with the outer @_ as arguments
                RuntimeList result = RuntimeCode.apply(perlMapClosure, mapArgs, RuntimeContextType.LIST);

                // Check for non-local return from map block
                if (result instanceof RuntimeControlFlowList cfList
                        && cfList.getControlFlowType() == ControlFlowType.RETURN) {
                    throw new PerlNonLocalReturnException(cfList.getReturnValue());
                }

                // `result` list contains aliases to the original array;
                // We need to make copies of the result elements
                RuntimeArray arr = new RuntimeArray();
                result.addToArray(arr);

                // Add all elements of the result list to the transformed list
                transformedElements.addAll(arr.elements);
            }

            // Create a new RuntimeList to hold the transformed elements
            RuntimeList transformedList = new RuntimeList();
            transformedList.elements = transformedElements;

            // Return based on context
            if (ctx == RuntimeContextType.SCALAR) {
                // Scalar context - return count of matching elements
                return new RuntimeList(new RuntimeScalar(transformedList.countElements()));
            } else {
                // List context - return the filtered RuntimeList
                return transformedList;
            }
        } finally {
            GlobalVariable.aliasGlobalVariable("main::_", saveValue);
            releaseEphemeralCaptures(perlMapClosure);
        }
    }

    /**
     * Legacy map method for backward compatibility.
     * Calls the new map with null outer args.
     */
    public static RuntimeList map(RuntimeList runtimeList, RuntimeScalar perlMapClosure, int ctx) {
        return map(runtimeList, perlMapClosure, null, ctx);
    }

    /**
     * Sorts the elements of this RuntimeArray using a Perl comparator subroutine.
     *
     * @param runtimeList
     * @param perlComparatorClosure A RuntimeScalar representing the Perl comparator subroutine.
     * @return A new RuntimeList with the elements sorted according to the Perl comparator.
     * @throws RuntimeException If the Perl comparator subroutine throws an exception.
     */
    public static RuntimeList sort(RuntimeList runtimeList, RuntimeScalar perlComparatorClosure, String packageName) {

        // Check each element to ensure it's not an undefined array reference
        runtimeList.validateNoAutovivification();

        // Create a new list from the elements of this RuntimeArray
        RuntimeArray array = runtimeList.getArrayOfAlias();

        // If comparator is a string (subroutine name), resolve it to a code reference
        RuntimeScalar comparator = perlComparatorClosure;
        if (comparator.type == RuntimeScalarType.STRING ||
                comparator.type == RuntimeScalarType.BYTE_STRING) {
            String subName = comparator.toString();
            if (!subName.contains("::")) {
                subName = packageName + "::" + subName;
            }
            comparator = GlobalVariable.getGlobalCodeRef(subName);
        }
        final RuntimeScalar finalComparator = comparator;

        // Check if comparator has $$ prototype (stacked comparator)
        // In Perl 5, $$-prototyped sort subs receive elements via @_ instead of $a/$b
        boolean isStacked = false;
        if (finalComparator.value instanceof RuntimeCode runtimeCode) {
            isStacked = "$$".equals(runtimeCode.prototype);
        }
        final boolean stackedComparator = isStacked;

        // Create the sort variables
        RuntimeScalar varA = getGlobalVariable(packageName + "::a");
        RuntimeScalar varB = getGlobalVariable(packageName + "::b");

        // Sort the new array using the Perl comparator subroutine
        array.elements.sort((a, b) -> {
            try {
                // Create $a, $b arguments for the comparator
                varA.set(a);
                varB.set(b);

                // For $$-prototyped comparators, pass elements via @_
                RuntimeArray comparatorArgs = new RuntimeArray();
                if (stackedComparator) {
                    comparatorArgs.push(a);
                    comparatorArgs.push(b);
                }

                // Apply the Perl comparator subroutine with the arguments
                RuntimeList result = RuntimeCode.apply(finalComparator, comparatorArgs, RuntimeContextType.SCALAR);

                // Check for control flow markers (goto/last/next/redo) that tried to escape the sort block.
                // The marker propagates as the return value (RuntimeControlFlowList), not via the registry.
                if (result.isNonLocalGoto()) {
                    ControlFlowType cfType = ((RuntimeControlFlowList) result).getControlFlowType();
                    String keyword = switch (cfType) {
                        case GOTO, TAILCALL -> "goto";
                        case LAST -> "last";
                        case NEXT -> "next";
                        case REDO -> "redo";
                        case RETURN -> "return";
                    };
                    throw new PerlCompilerException("Can't \"" + keyword + "\" out of a pseudo block");
                }

                // Retrieve the comparison result and return it as an integer
                return result.getFirst().getInt();
            } catch (PerlExitException e) {
                // exit() should propagate immediately - don't wrap it
                throw e;
            } catch (PerlCompilerException e) {
                // Propagate Perl errors directly so eval {} can catch them
                throw e;
            } catch (Exception e) {
                // Wrap any exceptions thrown by the comparator in a RuntimeException
                throw new RuntimeException(e);
            }
        });

        // Create a new RuntimeList to hold the sorted elements
        RuntimeList sortedList = new RuntimeList(array);

        // Release captures from ephemeral sort block closure
        releaseEphemeralCaptures(perlComparatorClosure);

        // Return the sorted RuntimeList
        return sortedList;
    }

    /**
     * Filters the elements of this RuntimeArray using a Perl subroutine.
     * This version passes the outer @_ to the grep block for Perl compatibility.
     *
     * @param runtimeList
     * @param perlFilterClosure A RuntimeScalar representing the Perl filter subroutine.
     * @param outerArgs The @_ from the surrounding subroutine (can be null for top-level code)
     * @param ctx The calling context
     * @return A new RuntimeList with the elements that match the filter criteria.
     * @throws RuntimeException If the Perl filter subroutine throws an exception.
     */
    public static RuntimeList grep(RuntimeList runtimeList, RuntimeScalar perlFilterClosure, RuntimeArray outerArgs, int ctx) {
        // Create a new list to hold the filtered elements
        List<RuntimeBase> filteredElements = new ArrayList<>();

        RuntimeScalar saveValue = getGlobalVariable("main::_");

        try {
            // Use the outer @_ instead of an empty array
            RuntimeArray filterArgs = outerArgs != null ? outerArgs : new RuntimeArray();

            // Iterate over each element in the current RuntimeArray
            for (RuntimeScalar element : runtimeList) {
                try {
                    // Create $_ argument for the filter subroutine
                    GlobalVariable.aliasGlobalVariable("main::_", element);

                    // Apply the Perl filter subroutine with the outer @_ as arguments
                    RuntimeList result = RuntimeCode.apply(perlFilterClosure, filterArgs, RuntimeContextType.SCALAR);

                    // Check for non-local return from grep block
                    if (result instanceof RuntimeControlFlowList cfList
                            && cfList.getControlFlowType() == ControlFlowType.RETURN) {
                        throw new PerlNonLocalReturnException(cfList.getReturnValue());
                    }

                    // Check the result of the filter subroutine
                    if (result.getFirst().getBoolean()) {
                        // Perl semantics: grep returns aliases to the original
                        // elements (not copies). This is required for patterns
                        // like `for (grep { !ref } $a, $b) { $_ = ... }` which
                        // modifies $a and $b. Cloning here would silently
                        // break that aliasing — see Class::MOP::MiniTrait.
                        filteredElements.add(element);
                    }
                } catch (PerlNonLocalReturnException e) {
                    throw e;  // Don't wrap non-local returns
                } catch (Exception e) {
                    // Wrap any exceptions thrown by the filter subroutine in a RuntimeException
                    throw new RuntimeException(e);
                }
            }

            // Create a new RuntimeList to hold the filtered elements
            RuntimeList filteredList = new RuntimeList();
            filteredList.elements = filteredElements;

            // Return based on context
            if (ctx == RuntimeContextType.SCALAR) {
                // Scalar context - return count of matching elements
                return new RuntimeList(new RuntimeScalar(filteredList.countElements()));
            } else {
                // List context - return the filtered RuntimeList
                return filteredList;
            }
        } finally {
            GlobalVariable.aliasGlobalVariable("main::_", saveValue);
            releaseEphemeralCaptures(perlFilterClosure);
        }
    }

    /**
     * Legacy grep method for backward compatibility.
     */
    public static RuntimeList grep(RuntimeList runtimeList, RuntimeScalar perlFilterClosure, int ctx) {
        return grep(runtimeList, perlFilterClosure, null, ctx);
    }

    /**
     * Check the elements of this RuntimeArray using a Perl subroutine.
     * This version passes the outer @_ to the all block for Perl compatibility.
     *
     * @param runtimeList
     * @param perlFilterClosure A RuntimeScalar representing the Perl filter subroutine.
     * @param outerArgs The @_ from the surrounding subroutine (can be null for top-level code)
     * @param ctx The calling context
     * @return A new RuntimeScalar boolean true if all elements match the filter criteria.
     * @throws RuntimeException If the Perl filter subroutine throws an exception.
     */
    public static RuntimeList all(RuntimeList runtimeList, RuntimeScalar perlFilterClosure, RuntimeArray outerArgs, int ctx) {

        RuntimeScalar saveValue = getGlobalVariable("main::_");

        try {
            RuntimeArray filterArgs = outerArgs != null ? outerArgs : new RuntimeArray();

            // Iterate over each element in the current RuntimeArray
            for (RuntimeScalar element : runtimeList) {
                try {
                    // Create $_ argument for the filter subroutine
                    GlobalVariable.aliasGlobalVariable("main::_", element);

                    // Apply the Perl filter subroutine with the argument
                    RuntimeList result = RuntimeCode.apply(perlFilterClosure, filterArgs, RuntimeContextType.SCALAR);

                    // Check for non-local return from all block
                    if (result instanceof RuntimeControlFlowList cfList
                            && cfList.getControlFlowType() == ControlFlowType.RETURN) {
                        throw new PerlNonLocalReturnException(cfList.getReturnValue());
                    }

                    // Check the result of the filter subroutine
                    if (!result.getFirst().getBoolean()) {
                        return scalarFalse.getList();
                    }
                } catch (PerlNonLocalReturnException e) {
                    throw e;  // Don't wrap non-local returns
                } catch (Exception e) {
                    // Wrap any exceptions thrown by the filter subroutine in a RuntimeException
                    throw new RuntimeException(e);
                }
            }

            return scalarTrue.getList();
        } finally {
            GlobalVariable.aliasGlobalVariable("main::_", saveValue);
            releaseEphemeralCaptures(perlFilterClosure);
        }
    }

    /**
     * Legacy all method for backward compatibility.
     */
    public static RuntimeList all(RuntimeList runtimeList, RuntimeScalar perlFilterClosure, int ctx) {
        return all(runtimeList, perlFilterClosure, null, ctx);
    }

    /**
     * Check the elements of this RuntimeArray using a Perl subroutine.
     * This version passes the outer @_ to the any block for Perl compatibility.
     *
     * @param runtimeList
     * @param perlFilterClosure A RuntimeScalar representing the Perl filter subroutine.
     * @param outerArgs The @_ from the surrounding subroutine (can be null for top-level code)
     * @param ctx The calling context
     * @return A new RuntimeScalar boolean true if any elements match the filter criteria.
     * @throws RuntimeException If the Perl filter subroutine throws an exception.
     */
    public static RuntimeList any(RuntimeList runtimeList, RuntimeScalar perlFilterClosure, RuntimeArray outerArgs, int ctx) {

        RuntimeScalar saveValue = getGlobalVariable("main::_");

        try {
            RuntimeArray filterArgs = outerArgs != null ? outerArgs : new RuntimeArray();

            // Iterate over each element in the current RuntimeArray
            for (RuntimeScalar element : runtimeList) {
                try {
                    // Create $_ argument for the filter subroutine
                    GlobalVariable.aliasGlobalVariable("main::_", element);

                    // Apply the Perl filter subroutine with the argument
                    RuntimeList result = RuntimeCode.apply(perlFilterClosure, filterArgs, RuntimeContextType.SCALAR);

                    // Check for non-local return from any block
                    if (result instanceof RuntimeControlFlowList cfList
                            && cfList.getControlFlowType() == ControlFlowType.RETURN) {
                        throw new PerlNonLocalReturnException(cfList.getReturnValue());
                    }

                    // Check the result of the filter subroutine
                    if (result.getFirst().getBoolean()) {
                        return scalarTrue.getList();
                    }
                } catch (PerlNonLocalReturnException e) {
                    throw e;  // Don't wrap non-local returns
                } catch (Exception e) {
                    // Wrap any exceptions thrown by the filter subroutine in a RuntimeException
                    throw new RuntimeException(e);
                }
            }

            return scalarFalse.getList();
        } finally {
            GlobalVariable.aliasGlobalVariable("main::_", saveValue);
            releaseEphemeralCaptures(perlFilterClosure);
        }
    }

    /**
     * Legacy any method for backward compatibility.
     */
    public static RuntimeList any(RuntimeList runtimeList, RuntimeScalar perlFilterClosure, int ctx) {
        return any(runtimeList, perlFilterClosure, null, ctx);
    }
}
