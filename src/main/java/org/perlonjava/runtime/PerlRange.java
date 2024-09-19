package org.perlonjava.runtime;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class PerlRange extends RuntimeBaseEntity implements RuntimeDataProvider, Iterable<RuntimeScalar> {

// XXX TODO (pseudocode)
//
// Create a flip-flop range
//    RuntimeScalar start = new RuntimeScalar("$_ =~ /start/");
//    RuntimeScalar end = new RuntimeScalar("$_ =~ /end/");
//    PerlRange flipFlop = PerlRange.createFlipFlop(start, end);
//
//    // Simulate processing lines of text
//    String[] lines = {
//            "before start",
//            "start",
//            "in between",
//            "still in between",
//            "end",
//            "after end",
//            "start again",
//            "middle",
//            "end again",
//            "final line"
//    };
//
//        for (String line : lines) {
//        // Set the current line in a global context (simulating Perl's $_)
//        GlobalContext.set("_", new RuntimeScalar(line));
//
//        // Evaluate the flip-flop condition
//        boolean isActive = flipFlop.iterator().next().getBoolean();
//
//        System.out.println(line + " : " + (isActive ? "ACTIVE" : "inactive"));
//    }


    private final RuntimeScalar start;
    private final RuntimeScalar end;
    private boolean isFlipFlop;
    private boolean flipFlopState;
    private final boolean isNumeric;

    public PerlRange(RuntimeScalar start, RuntimeScalar end) {
        this.start = start;
        this.end = end;
        this.isFlipFlop = false;
        this.flipFlopState = false;

        // TODO - more rules for String behaviour:
        // If left-hand string begins with 0 and is longer than one character,
        // If the initial value specified isn't part of a magical increment sequence (that is, a non-empty string matching /^[a-zA-Z]*[0-9]*\z/), only the initial value will be returned.
        // For example, "ax".."az" produces "ax", "ay", "az", but "*x".."az" produces only "*x".
        this.isNumeric = start.looksLikeNumber() && end.looksLikeNumber();
    }

    public static PerlRange createRange(RuntimeScalar start, RuntimeScalar end) {
        return new PerlRange(start, end);
    }

    public static PerlRange createFlipFlop(RuntimeScalar start, RuntimeScalar end) {
        PerlRange range = new PerlRange(start, end);
        range.isFlipFlop = true;
        return range;
    }

    @Override
    public Iterator<RuntimeScalar> iterator() {
        return new PerlRangeIterator();
    }

    /**
     * @return
     */
    @Override
    public RuntimeDataProvider undefine() {
        return toList().undefine();
    }

    private class PerlRangeIterator implements Iterator<RuntimeScalar> {
        private RuntimeScalar current;
        private boolean hasNext;
        private String endString;

        PerlRangeIterator() {
            if (isFlipFlop) {
                hasNext = true;
                current = null;
            } else {
                current = new RuntimeScalar(start);
                if (isNumeric) {
                    hasNext = current.getInt() <= end.getInt();
                } else {
                    endString = end.toString();
                    String currentString = current.toString();
                    hasNext = currentString.length() < endString.length() ||
                              (currentString.length() == endString.length() &&
                               currentString.compareTo(endString) <= 0);
                }
            }
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

            return isFlipFlop ? handleFlipFlop() : handleRange();
        }

        private RuntimeScalar handleFlipFlop() {
            RuntimeScalar result = current;
            if (start.getBoolean()) {
                flipFlopState = true;
            }
            if (flipFlopState) {
                current = new RuntimeScalar(true);
                if (end.getBoolean()) {
                    flipFlopState = false;
                }
            } else {
                current = new RuntimeScalar(false);
            }
            return result != null ? result : current;
        }

        private RuntimeScalar handleRange() {
            RuntimeScalar result = current;
            if (isNumeric) {
                if (current.getInt() < end.getInt()) {
                    current = current.clone().preAutoIncrement();
                } else {
                    hasNext = false;
                }
            } else {
                String currentString = current.toString();
                if (currentString.length() < endString.length() ||
                    (currentString.length() == endString.length() &&
                     currentString.compareTo(endString) < 0)) {
                    current = current.clone().preAutoIncrement();
                } else {
                    hasNext = false;
                }
            }
            return result;
        }
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
}