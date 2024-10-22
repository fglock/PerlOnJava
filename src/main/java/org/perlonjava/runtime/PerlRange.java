package org.perlonjava.runtime;

import java.util.Iterator;
import java.util.NoSuchElementException;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarEmptyString;

public class PerlRange extends RuntimeBaseEntity implements RuntimeDataProvider, Iterable<RuntimeScalar> {
    private final RuntimeScalar start;
    private final RuntimeScalar end;

    public PerlRange(RuntimeScalar start, RuntimeScalar end) {
        this.start = start;
        this.end = end;
    }

    public static PerlRange createRange(RuntimeScalar start, RuntimeScalar end) {
        return new PerlRange(start, end);
    }

    @Override
    public Iterator<RuntimeScalar> iterator() {
        if (start.type == RuntimeScalarType.INTEGER) {
            // most common case
            return new PerlRangeIntegerIterator();
        }
        String startString = start.toString();
        if (start.looksLikeNumber() && end.looksLikeNumber()) {
            if (startString.length() > 1 && startString.startsWith("0")) {
                // "01" is String-like
            } else {
                return new PerlRangeIntegerIterator();
            }
        }
        // more rules for String behaviour:
        // If left-hand string begins with 0 and is longer than one character,
        // If the initial value specified isn't part of a magical increment sequence (that is, a non-empty string matching /^[a-zA-Z]*[0-9]*\z/), only the initial value will be returned.
        // For example, "ax".."az" produces "ax", "ay", "az", but "*x".."az" produces only "*x".
        String endString = end.toString();
        if (startString.isEmpty()) {
            return scalarEmptyString.iterator();
        } else if (!startString.matches("^[a-zA-Z]*[0-9]*\\z")) {
            return start.iterator();
        } else if (startString.length() > endString.length()
                || startString.compareTo(endString) >= 0) {
            return start.iterator();
        }
        return new PerlRangeStringIterator();
    }

    /**
     * @return
     */
    @Override
    public RuntimeDataProvider undefine() {
        return toList().undefine();
    }

    @Override
    public String toString() {
        return toList().toString();
    }

    /**
     * @param arr
     * @return
     */
    @Override
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        return toList().setArrayOfAlias(arr);
    }

    /**
     * @return
     */
    @Override
    public RuntimeList getList() {
        return toList();
    }

    /**
     * @return
     */
    @Override
    public RuntimeScalar scalar() {
        return end;
    }

    @Override
    public boolean getBoolean() {
        return this.scalar().getBoolean();
    }

    public boolean getDefinedBoolean() {
        return true;
    }

    /**
     * @return
     */
    @Override
    public RuntimeScalar createReference() {
        return toList().createReference();
    }

    /**
     * @param array The RuntimeArray object
     */
    @Override
    public void addToArray(RuntimeArray array) {
        toList().addToArray(array);
    }

    /**
     * @return
     */
    @Override
    public int countElements() {
        return toList().countElements();
    }

    /**
     * @param scalar The RuntimeScalar object
     * @return
     */
    @Override
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return toList().addToScalar(scalar);
    }

    /**
     * @param list The RuntimeList object
     * @return
     */
    @Override
    public RuntimeArray setFromList(RuntimeList list) {
        return toList().setFromList(list);
    }

    /**
     * @return
     */
    @Override
    public RuntimeArray keys() {
        return toList().keys();
    }

    /**
     * @return
     */
    @Override
    public RuntimeArray values() {
        return toList().values();
    }

    /**
     * @return
     */
    @Override
    public RuntimeList each() {
        return toList().each();
    }

    /**
     * @return
     */
    @Override
    public RuntimeScalar chop() {
        return toList().chop();
    }

    /**
     * @return
     */
    @Override
    public RuntimeScalar chomp() {
        return toList().chomp();
    }

    public RuntimeList toList() {
        Iterator<RuntimeScalar> it = this.iterator();
        RuntimeList list = new RuntimeList();
        while (it.hasNext()) {
            list.add(it.next());
        }
        return list;
    }

    /**
     * Saves the current state of the instance.
     *
     * <p>This method creates a snapshot of the current value,
     * and pushes it onto a static stack for later restoration. After saving, it clears
     * the current elements and resets the value.
     */
    @Override
    public void dynamicSaveState() {
        throw new PerlCompilerException("not implemented: local RANGE");
    }

    /**
     * Restores the most recently saved state of the instance.
     *
     * <p>This method pops the most recent state from the static stack and restores
     * the value. If no state is saved, it does nothing.
     */
    @Override
    public void dynamicRestoreState() {
        throw new PerlCompilerException("not implemented: local RANGE");
    }

    private class PerlRangeStringIterator implements Iterator<RuntimeScalar> {
        private final String endString;
        private String current;
        private boolean hasNext;

        PerlRangeStringIterator() {
            current = start.toString();
            endString = end.toString();
            hasNext = current.length() < endString.length() ||
                    (current.length() == endString.length() &&
                            current.compareTo(endString) <= 0);
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public RuntimeScalar next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            RuntimeScalar res = new RuntimeScalar(current);
            if (current.length() < endString.length() ||
                    (current.length() == endString.length() &&
                            current.compareTo(endString) < 0)) {
                current = ScalarUtils.incrementPlainString(current);
            } else {
                hasNext = false;
            }
            return res;
        }
    }

    private class PerlRangeIntegerIterator implements Iterator<RuntimeScalar> {
        private final int endInt;
        private int current;
        private boolean hasNext;

        PerlRangeIntegerIterator() {
            current = start.getInt();
            endInt = end.getInt();
            hasNext = current <= endInt;
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public RuntimeScalar next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            RuntimeScalar result = getScalarInt(current);
            if (current < endInt) {
                current++;
            } else {
                hasNext = false;
            }
            return result;
        }
    }
}
