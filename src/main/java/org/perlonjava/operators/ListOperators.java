package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

public class ListOperators {
    /**
     * Transforms the elements of this RuntimeArray using a Perl subroutine.
     *
     * @param runtimeList
     * @param perlMapClosure A RuntimeScalar representing the Perl map subroutine.
     * @return A new RuntimeList with the transformed elements.
     * @throws RuntimeException If the Perl map subroutine throws an exception.
     */
    public static RuntimeList map(RuntimeList runtimeList, RuntimeScalar perlMapClosure, int ctx) {

        // Create a new list to hold the transformed elements
        List<RuntimeBase> transformedElements = new ArrayList<>();

        RuntimeScalar saveValue = getGlobalVariable("main::_");

        RuntimeArray mapArgs = new RuntimeArray();

        // Iterate over each element in the current RuntimeArray
        for (RuntimeScalar element : runtimeList) {
                try {
                    // Create $_ argument for the map subroutine
                    GlobalVariable.aliasGlobalVariable("main::_", element);

                    // Apply the Perl map subroutine with the argument
                    RuntimeList result = RuntimeCode.apply(perlMapClosure, mapArgs, RuntimeContextType.LIST);

                    // `result` list contains aliases to the original array;
                    // We need to make copies of the result elements
                    RuntimeArray arr = new RuntimeArray();
                    result.addToArray(arr);

                    // Add all elements of the result list to the transformed list
                    transformedElements.addAll(arr.elements);
                } catch (Exception e) {
                    // Wrap any exceptions thrown by the map subroutine in a RuntimeException
                    throw new RuntimeException(e);
                }
        }

        // Create a new RuntimeList to hold the transformed elements
        RuntimeList transformedList = new RuntimeList();
        transformedList.elements = transformedElements;

        GlobalVariable.aliasGlobalVariable("main::_", saveValue);

        // Return based on context
        if (ctx == RuntimeContextType.SCALAR) {
            // Scalar context - return count of matching elements
            return new RuntimeList(new RuntimeScalar(transformedList.countElements()));
        } else {
            // List context - return the filtered RuntimeList
            return transformedList;
        }
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

        String $a = packageName + "::a";
        String $b = packageName + "::b";

        // Check each element to ensure it's not an undefined array reference
        runtimeList.validateNoAutovivification();

        // Create a new list from the elements of this RuntimeArray
        RuntimeArray array = runtimeList.getArrayOfAlias();

        RuntimeScalar saveValueA = getGlobalVariable($a);
        RuntimeScalar saveValueB = getGlobalVariable($b);

        RuntimeArray comparatorArgs = new RuntimeArray();

        // Sort the new array using the Perl comparator subroutine
        array.elements.sort((a, b) -> {
            try {
                // Create $a, $b arguments for the comparator
                GlobalVariable.aliasGlobalVariable($a, a);
                GlobalVariable.aliasGlobalVariable($b, b);

                // Apply the Perl comparator subroutine with the arguments
                RuntimeList result = RuntimeCode.apply(perlComparatorClosure, comparatorArgs, RuntimeContextType.SCALAR);

                // Retrieve the comparison result and return it as an integer
                return result.getFirst().getInt();
            } catch (Exception e) {
                // Wrap any exceptions thrown by the comparator in a RuntimeException
                throw new RuntimeException(e);
            }
        });

        // Create a new RuntimeList to hold the sorted elements
        RuntimeList sortedList = new RuntimeList(array);

        GlobalVariable.aliasGlobalVariable($a, saveValueA);
        GlobalVariable.aliasGlobalVariable($b, saveValueB);

        // Return the sorted RuntimeList
        return sortedList;
    }

    /**
     * Filters the elements of this RuntimeArray using a Perl subroutine.
     *
     * @param runtimeList
     * @param perlFilterClosure A RuntimeScalar representing the Perl filter subroutine.
     * @return A new RuntimeList with the elements that match the filter criteria.
     * @throws RuntimeException If the Perl filter subroutine throws an exception.
     */
    public static RuntimeList grep(RuntimeList runtimeList, RuntimeScalar perlFilterClosure, int ctx) {
        // Create a new list to hold the filtered elements
        List<RuntimeBase> filteredElements = new ArrayList<>();

        RuntimeScalar saveValue = getGlobalVariable("main::_");

        RuntimeArray filterArgs = new RuntimeArray();

        // Iterate over each element in the current RuntimeArray
        for (RuntimeScalar element : runtimeList) {
            try {
                // Create $_ argument for the filter subroutine
                GlobalVariable.aliasGlobalVariable("main::_", element);

                // Apply the Perl filter subroutine with the argument
                RuntimeList result = RuntimeCode.apply(perlFilterClosure, filterArgs, RuntimeContextType.SCALAR);

                // Check the result of the filter subroutine
                if (result.getFirst().getBoolean()) {
                    // If the result is non-zero, add the element to the filtered list
                    // We need to clone, otherwise we would be adding an alias to the original element
                    filteredElements.add(element.clone());
                }
            } catch (Exception e) {
                // Wrap any exceptions thrown by the filter subroutine in a RuntimeException
                throw new RuntimeException(e);
            }
        }

        // Create a new RuntimeList to hold the filtered elements
        RuntimeList filteredList = new RuntimeList();
        filteredList.elements = filteredElements;

        GlobalVariable.aliasGlobalVariable("main::_", saveValue);

        // Return based on context
        if (ctx == RuntimeContextType.SCALAR) {
            // Scalar context - return count of matching elements
            return new RuntimeList(new RuntimeScalar(filteredList.countElements()));
        } else {
            // List context - return the filtered RuntimeList
            return filteredList;
        }
    }

    /**
     * Check the elements of this RuntimeArray using a Perl subroutine.
     *
     * @param runtimeList
     * @param perlFilterClosure A RuntimeScalar representing the Perl filter subroutine.
     * @return A new RuntimeScalar boolean true if all elements match the filter criteria.
     * @throws RuntimeException If the Perl filter subroutine throws an exception.
     */
    public static RuntimeList all(RuntimeList runtimeList, RuntimeScalar perlFilterClosure, int ctx) {

        RuntimeScalar saveValue = getGlobalVariable("main::_");

        RuntimeArray filterArgs = new RuntimeArray();

        // Iterate over each element in the current RuntimeArray
        for (RuntimeScalar element : runtimeList) {
            try {
                // Create $_ argument for the filter subroutine
                GlobalVariable.aliasGlobalVariable("main::_", element);

                // Apply the Perl filter subroutine with the argument
                RuntimeList result = RuntimeCode.apply(perlFilterClosure, filterArgs, RuntimeContextType.SCALAR);

                // Check the result of the filter subroutine
                if (!result.getFirst().getBoolean()) {
                    return scalarFalse.getList();
                }
            } catch (Exception e) {
                // Wrap any exceptions thrown by the filter subroutine in a RuntimeException
                throw new RuntimeException(e);
            }
        }

        GlobalVariable.aliasGlobalVariable("main::_", saveValue);

        return scalarTrue.getList();
    }

    /**
     * Check the elements of this RuntimeArray using a Perl subroutine.
     *
     * @param runtimeList
     * @param perlFilterClosure A RuntimeScalar representing the Perl filter subroutine.
     * @return A new RuntimeScalar boolean true if any elements match the filter criteria.
     * @throws RuntimeException If the Perl filter subroutine throws an exception.
     */
    public static RuntimeList any(RuntimeList runtimeList, RuntimeScalar perlFilterClosure, int ctx) {

        RuntimeScalar saveValue = getGlobalVariable("main::_");

        RuntimeArray filterArgs = new RuntimeArray();

        // Iterate over each element in the current RuntimeArray
        for (RuntimeScalar element : runtimeList) {
            try {
                // Create $_ argument for the filter subroutine
                GlobalVariable.aliasGlobalVariable("main::_", element);

                // Apply the Perl filter subroutine with the argument
                RuntimeList result = RuntimeCode.apply(perlFilterClosure, filterArgs, RuntimeContextType.SCALAR);

                // Check the result of the filter subroutine
                if (result.getFirst().getBoolean()) {
                    return scalarTrue.getList();
                }
            } catch (Exception e) {
                // Wrap any exceptions thrown by the filter subroutine in a RuntimeException
                throw new RuntimeException(e);
            }
        }

        GlobalVariable.aliasGlobalVariable("main::_", saveValue);

        return scalarFalse.getList();
    }
}
