package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/** Java implementation of the iterator primitives from Algorithm::Combinatorics XS. */
public class AlgorithmCombinatorics extends PerlModuleBase {

    public static final String XS_VERSION = "0.27";

    public AlgorithmCombinatorics() {
        super("Algorithm::Combinatorics", false);
    }

    public static void initialize() {
        AlgorithmCombinatorics module = new AlgorithmCombinatorics();
        GlobalVariable.getGlobalVariable("Algorithm::Combinatorics::VERSION").set(XS_VERSION);
        try {
            module.registerMethod("__next_combination", null);
            module.registerMethod("__next_combination_with_repetition", null);
            module.registerMethod("__next_variation", null);
            module.registerMethod("__next_variation_with_repetition", null);
            module.registerMethod("__next_variation_with_repetition_gray_code", null);
            module.registerMethod("__next_permutation", null);
            module.registerMethod("__next_permutation_heap", null);
            module.registerMethod("__next_derangement", null);
            module.registerMethod("__next_partition", null);
            module.registerMethod("__next_partition_of_size_p", null);
            module.registerMethod("__next_subset", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Incomplete Algorithm::Combinatorics implementation", e);
        }
    }

    private static int value(RuntimeArray array, int index) {
        return array.get(index).getInt();
    }

    private static void set(RuntimeArray array, int index, int value) {
        array.elements.set(index, new RuntimeScalar(value));
    }

    private static void swap(RuntimeArray array, int left, int right) {
        int value = value(array, left);
        set(array, left, value(array, right));
        set(array, right, value);
    }

    private static RuntimeList integer(int value) {
        return new RuntimeScalar(value).getList();
    }

    public static RuntimeList __next_combination(RuntimeArray args, int ctx) {
        RuntimeArray tuple = args.get(0).arrayDeref();
        int max = args.get(1).getInt();
        int last = tuple.size() - 1;
        int offset = max - last;
        for (int i = last; i >= 0; i--) {
            int n = value(tuple, i);
            if (n < i + offset) {
                set(tuple, i, ++n);
                for (int j = i + 1; j <= last; j++) set(tuple, j, ++n);
                return integer(i);
            }
        }
        return integer(-1);
    }

    public static RuntimeList __next_combination_with_repetition(RuntimeArray args, int ctx) {
        RuntimeArray tuple = args.get(0).arrayDeref();
        int max = args.get(1).getInt();
        int last = tuple.size() - 1;
        for (int i = last; i >= 0; i--) {
            int n = value(tuple, i);
            if (n < max) {
                n++;
                for (int j = i; j <= last; j++) set(tuple, j, n);
                return integer(i);
            }
        }
        return integer(-1);
    }

    public static RuntimeList __next_variation(RuntimeArray args, int ctx) {
        RuntimeArray tuple = args.get(0).arrayDeref();
        RuntimeArray used = args.get(1).arrayDeref();
        int max = args.get(2).getInt();
        int last = tuple.size() - 1;
        for (int i = last; i >= 0; i--) {
            int n = value(tuple, i);
            set(used, n, 0);
            while (++n <= max) {
                if (value(used, n) == 0) {
                    set(tuple, i, n);
                    set(used, n, 1);
                    for (int j = i + 1; j <= last; j++) {
                        n = -1;
                        while (++n <= max) {
                            if (value(used, n) == 0) {
                                set(tuple, j, n);
                                set(used, n, 1);
                                break;
                            }
                        }
                    }
                    return integer(i);
                }
            }
        }
        return integer(-1);
    }

    public static RuntimeList __next_variation_with_repetition(RuntimeArray args, int ctx) {
        RuntimeArray tuple = args.get(0).arrayDeref();
        int max = args.get(1).getInt();
        for (int i = tuple.size() - 1; i >= 0; i--) {
            int n = value(tuple, i);
            if (n < max) {
                set(tuple, i, n + 1);
                return integer(i);
            }
            set(tuple, i, 0);
        }
        return integer(-1);
    }

    public static RuntimeList __next_variation_with_repetition_gray_code(RuntimeArray args, int ctx) {
        RuntimeArray tuple = args.get(0).arrayDeref();
        RuntimeArray focus = args.get(1).arrayDeref();
        RuntimeArray directions = args.get(2).arrayDeref();
        int max = args.get(3).getInt();
        int n = tuple.size();
        int j = value(focus, 0);
        set(focus, 0, 0);
        if (j == n) return integer(-1);
        set(tuple, j, value(tuple, j) + value(directions, j));
        int coordinate = value(tuple, j);
        if (coordinate == 0 || coordinate == max) {
            set(directions, j, -value(directions, j));
            set(focus, j, value(focus, j + 1));
            set(focus, j + 1, j + 1);
        }
        return integer(j);
    }

    public static RuntimeList __next_permutation(RuntimeArray args, int ctx) {
        RuntimeArray tuple = args.get(0).arrayDeref();
        int max = tuple.size() - 1;
        int j;
        for (j = max - 1; j >= 0 && value(tuple, j) > value(tuple, j + 1); j--) { }
        if (j == -1) return integer(-1);
        int selected = value(tuple, j);
        int h;
        for (h = max; selected > value(tuple, h); h--) { }
        swap(tuple, j, h);
        for (int k = j + 1, tail = max; k < tail; k++, tail--) swap(tuple, k, tail);
        return integer(1);
    }

    public static RuntimeList __next_permutation_heap(RuntimeArray args, int ctx) {
        RuntimeArray tuple = args.get(0).arrayDeref();
        RuntimeArray counters = args.get(1).arrayDeref();
        int n = tuple.size();
        int k = 1;
        int counter = value(counters, k);
        while (counter == k) {
            set(counters, k, 0);
            k++;
            counter = value(counters, k);
        }
        if (k == n) return integer(-1);
        counter++;
        set(counters, k, counter);
        if (k % 2 == 0) swap(tuple, k, 0);
        else swap(tuple, k, counter - 1);
        return integer(k);
    }

    public static RuntimeList __next_derangement(RuntimeArray args, int ctx) {
        RuntimeArray tuple = args.get(0).arrayDeref();
        int max = tuple.size() - 1;
        int minJ = max;
        while (true) {
            int j;
            for (j = max - 1; j >= 0 && value(tuple, j) > value(tuple, j + 1); j--) { }
            if (j == -1) return integer(-1);
            minJ = Math.min(minJ, j);
            int selected = value(tuple, j);
            int h;
            for (h = max; selected > value(tuple, h); h--) { }
            swap(tuple, j, h);
            if (value(tuple, j) == j) continue;
            for (int k = j + 1, tail = max; k < tail; k++, tail--) swap(tuple, k, tail);
            boolean fixed = false;
            for (int k = max; k > minJ; k--) {
                if (value(tuple, k) == k) {
                    fixed = true;
                    break;
                }
            }
            if (!fixed) return integer(1);
        }
    }

    public static RuntimeList __next_partition(RuntimeArray args, int ctx) {
        RuntimeArray groups = args.get(0).arrayDeref();
        RuntimeArray maxima = args.get(1).arrayDeref();
        int last = groups.size() - 1;
        for (int i = last; i > 0; i--) {
            if (value(groups, i) <= value(maxima, i - 1)) {
                int group = value(groups, i) + 1;
                set(groups, i, group);
                if (group > value(maxima, i)) set(maxima, i, group);
                int maximum = value(maxima, i);
                for (int j = i + 1; j <= last; j++) {
                    set(groups, j, 0);
                    set(maxima, j, maximum);
                }
                return integer(i);
            }
        }
        return integer(-1);
    }

    public static RuntimeList __next_partition_of_size_p(RuntimeArray args, int ctx) {
        RuntimeArray groups = args.get(0).arrayDeref();
        RuntimeArray maxima = args.get(1).arrayDeref();
        int partitions = args.get(2).getInt();
        int last = groups.size() - 1;
        for (int i = last; i > 0; i--) {
            int group = value(groups, i);
            if (group < partitions - 1 && group <= value(maxima, i - 1)) {
                group++;
                set(groups, i, group);
                if (group > value(maxima, i)) set(maxima, i, group);
                int nMinusP = last + 1 - partitions;
                int maximum = value(maxima, i);
                int x = nMinusP + maximum;
                for (int j = i + 1; j <= x; j++) {
                    set(groups, j, 0);
                    set(maxima, j, maximum);
                }
                for (int j = x + 1; j <= last; j++) {
                    int value = j - nMinusP;
                    set(groups, j, value);
                    set(maxima, j, value);
                }
                return integer(i);
            }
        }
        return integer(-1);
    }

    public static RuntimeList __next_subset(RuntimeArray args, int ctx) {
        RuntimeArray data = args.get(0).arrayDeref();
        RuntimeArray odometer = args.get(1).arrayDeref();
        RuntimeArray subset = new RuntimeArray();
        int adjust = 1;
        for (int i = 0; i < data.size(); i++) {
            int digit = value(odometer, i);
            if (digit != 0) subset.push(new RuntimeScalar(data.get(i)));
            if (adjust != 0) {
                adjust = 1 - digit;
                set(odometer, i, adjust);
            }
        }
        return subset.createReference().getList();
    }
}
