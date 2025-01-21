package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;

public class ListOperators {
    /**
     * Transforms the elements of this RuntimeArray using a Perl subroutine.
     *
     * @param runtimeList
     * @param perlMapClosure A RuntimeScalar representing the Perl map subroutine.
     * @return A new RuntimeList with the transformed elements.
     * @throws RuntimeException If the Perl map subroutine throws an exception.
     */
    public static RuntimeList map(RuntimeList runtimeList, RuntimeScalar perlMapClosure) {

        // Create a new list to hold the transformed elements
        List<RuntimeBaseEntity> transformedElements = new ArrayList<>();

        RuntimeScalar var_ = getGlobalVariable("main::_");
        RuntimeArray mapArgs = new RuntimeArray();

        // Iterate over each element in the current RuntimeArray
        Iterator<RuntimeScalar> iterator = runtimeList.iterator();
        while (iterator.hasNext()) {
            try {
                // Create $_ argument for the map subroutine
                var_.set(iterator.next());

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

        // Return the transformed RuntimeList
        return transformedList;
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
        // Create a new list from the elements of this RuntimeArray
        RuntimeArray array = runtimeList.getArrayOfAlias();

        RuntimeScalar varA = getGlobalVariable(packageName + "::a");
        RuntimeScalar varB = getGlobalVariable(packageName + "::b");
        RuntimeArray comparatorArgs = new RuntimeArray();

        // Sort the new array using the Perl comparator subroutine
        array.elements.sort((a, b) -> {
            try {
                // Create $a, $b arguments for the comparator
                varA.set(a);
                varB.set(b);

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
    public static RuntimeList grep(RuntimeList runtimeList, RuntimeScalar perlFilterClosure) {
        RuntimeArray array = runtimeList.getArrayOfAlias();

        // Create a new list to hold the filtered elements
        List<RuntimeBaseEntity> filteredElements = new ArrayList<>();

        RuntimeScalar var_ = getGlobalVariable("main::_");
        RuntimeArray filterArgs = new RuntimeArray();

        // Iterate over each element in the current RuntimeArray
        for (RuntimeScalar element : array.elements) {
            try {
                // Create $_ argument for the filter subroutine
                var_.set(element);

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

        // Return the filtered RuntimeList
        return filteredList;
    }
}
