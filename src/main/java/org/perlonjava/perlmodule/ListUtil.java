package org.perlonjava.perlmodule;

import org.perlonjava.runtime.operators.ListOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.*;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

/**
 * List::Util module implementation for PerlOnJava.
 * <p>
 * Copyright (c) 1997-2009 Graham Barr <gbarr@pobox.com>. All rights reserved.
 * This program is free software; you can redistribute it and/or
 * modify it under the same terms as Perl itself.
 * <p>
 * Maintained since 2013 by Paul Evans <leonerd@leonerd.org.uk>
 */
public class ListUtil extends PerlModuleBase {

    /**
     * Constructor for ListUtil module.
     */
    public ListUtil() {
        super("List::Util", true); // true for auto-export
    }

    /**
     * Initializes the List::Util module by registering methods and exports.
     */
    public static void initialize() {
        ListUtil listUtil = new ListUtil();
        try {
            // List reduction functions
            listUtil.registerMethod("reduce", "reduce", "&@");
            listUtil.registerMethod("reductions", "reductions", "&@");
            listUtil.registerMethod("any", "any", "&@");
            listUtil.registerMethod("all", "all", "&@");
            listUtil.registerMethod("none", "none", "&@");
            listUtil.registerMethod("notall", "notall", "&@");
            listUtil.registerMethod("first", "first", "&@");

            // Min/max functions
            listUtil.registerMethod("min", "min", "@");
            listUtil.registerMethod("max", "max", "@");
            listUtil.registerMethod("minstr", "minstr", "@");
            listUtil.registerMethod("maxstr", "maxstr", "@");

            // Arithmetic functions
            listUtil.registerMethod("sum", "sum", "@");
            listUtil.registerMethod("sum0", "sum0", "@");
            listUtil.registerMethod("product", "product", "@");

            // List manipulation
            listUtil.registerMethod("shuffle", "shuffle", "@");
            listUtil.registerMethod("sample", "sample", "$@");
            listUtil.registerMethod("uniq", "uniq", "@");
            listUtil.registerMethod("uniqint", "uniqint", "@");
            listUtil.registerMethod("uniqnum", "uniqnum", "@");
            listUtil.registerMethod("uniqstr", "uniqstr", "@");
            listUtil.registerMethod("head", "head", "$@");
            listUtil.registerMethod("tail", "tail", "$@");

            // Pair functions
            listUtil.registerMethod("pairs", "pairs", "@");
            listUtil.registerMethod("unpairs", "unpairs", "@");
            listUtil.registerMethod("pairkeys", "pairkeys", "@");
            listUtil.registerMethod("pairvalues", "pairvalues", "@");
            listUtil.registerMethod("pairmap", "pairmap", "&@");
            listUtil.registerMethod("pairgrep", "pairgrep", "&@");
            listUtil.registerMethod("pairfirst", "pairfirst", "&@");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing List::Util method: " + e.getMessage());
        }
    }

    /**
     * Helper method to create a RuntimeList from a subset of RuntimeArray elements.
     */
    private static RuntimeList createSubList(RuntimeArray args, int startIndex) {
        RuntimeList result = new RuntimeList();
        for (int i = startIndex; i < args.size(); i++) {
            result.add(args.get(i));
        }
        return result;
    }

    /**
     * Reduces a list by calling a block multiple times.
     */
    public static RuntimeList reduce(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarUndef.getList();
        }

        RuntimeScalar codeRef = args.get(0);
        RuntimeList values = createSubList(args, 1);

        if (values.size() == 0) {
            return scalarUndef.getList();
        }
        if (values.size() == 1) {
            return values.elements.get(0).scalar().getList();
        }

        RuntimeScalar varA = getGlobalVariable("main::a");
        RuntimeScalar varB = getGlobalVariable("main::b");
        RuntimeScalar saveA = varA.clone();
        RuntimeScalar saveB = varB.clone();

        try {
            RuntimeScalar accumulator = values.elements.get(0).scalar().clone();

            for (int i = 1; i < values.size(); i++) {
                varA.set(accumulator);
                varB.set(values.elements.get(i).scalar());

                RuntimeList result = RuntimeCode.apply(codeRef, new RuntimeArray(), RuntimeContextType.SCALAR);
                accumulator = result.getFirst();
            }

            return accumulator.getList();
        } finally {
            varA.set(saveA);
            varB.set(saveB);
        }
    }

    /**
     * Similar to reduce but returns intermediate values.
     */
    public static RuntimeList reductions(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeList();
        }

        RuntimeScalar codeRef = args.get(0);
        RuntimeList values = createSubList(args, 1);
        RuntimeArray results = new RuntimeArray();

        if (values.size() == 0) {
            return results.getList();
        }

        RuntimeScalar varA = getGlobalVariable("main::a");
        RuntimeScalar varB = getGlobalVariable("main::b");
        RuntimeScalar saveA = varA.clone();
        RuntimeScalar saveB = varB.clone();

        try {
            RuntimeScalar accumulator = values.elements.get(0).scalar().clone();
            results.push(accumulator.clone());

            for (int i = 1; i < values.size(); i++) {
                varA.set(accumulator);
                varB.set(values.elements.get(i).scalar());

                RuntimeList result = RuntimeCode.apply(codeRef, new RuntimeArray(), RuntimeContextType.SCALAR);
                accumulator = result.getFirst();
                results.push(accumulator.clone());
            }

            return results.getList();
        } finally {
            varA.set(saveA);
            varB.set(saveB);
        }
    }

    /**
     * Returns true if any element makes the block return true.
     */
    public static RuntimeList any(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarFalse.getList();
        }

        RuntimeScalar codeRef = args.get(0);
        RuntimeList values = createSubList(args, 1);

        return ListOperators.any(values, codeRef, ctx);
    }

    /**
     * Returns true if all elements make the block return true.
     */
    public static RuntimeList all(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarTrue.getList();
        }

        RuntimeScalar codeRef = args.get(0);
        RuntimeList values = createSubList(args, 1);

        return ListOperators.all(values, codeRef, ctx);
    }

    /**
     * Returns true if no elements make the block return true.
     */
    public static RuntimeList none(RuntimeArray args, int ctx) {
        RuntimeList result = any(args, ctx);
        return result.getFirst().getBoolean() ? scalarFalse.getList() : scalarTrue.getList();
    }

    /**
     * Returns true if not all elements make the block return true.
     */
    public static RuntimeList notall(RuntimeArray args, int ctx) {
        RuntimeList result = all(args, ctx);
        return result.getFirst().getBoolean() ? scalarFalse.getList() : scalarTrue.getList();
    }

    /**
     * Returns the first element where the block returns true.
     */
    public static RuntimeList first(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarUndef.getList();
        }

        RuntimeScalar codeRef = args.get(0);
        RuntimeList values = createSubList(args, 1);
        RuntimeScalar saveValue = getGlobalVariable("main::_");

        try {
            for (RuntimeBase element : values.elements) {
                RuntimeScalar scalar = element.scalar();
                GlobalVariable.aliasGlobalVariable("main::_", scalar);

                RuntimeList result = RuntimeCode.apply(codeRef, new RuntimeArray(), RuntimeContextType.SCALAR);
                if (result.getFirst().getBoolean()) {
                    return scalar.getList();
                }
            }
            return scalarUndef.getList();
        } finally {
            GlobalVariable.aliasGlobalVariable("main::_", saveValue);
        }
    }

    /**
     * Returns the minimum numerical value.
     */
    public static RuntimeList min(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        RuntimeScalar min = args.get(0);
        for (int i = 1; i < args.size(); i++) {
            RuntimeScalar current = args.get(i);
            if (current.getDouble() < min.getDouble()) {
                min = current;
            }
        }
        return min.getList();
    }

    /**
     * Returns the maximum numerical value.
     */
    public static RuntimeList max(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        RuntimeScalar max = args.get(0);
        for (int i = 1; i < args.size(); i++) {
            RuntimeScalar current = args.get(i);
            if (current.getDouble() > max.getDouble()) {
                max = current;
            }
        }
        return max.getList();
    }

    /**
     * Returns the minimum string value.
     */
    public static RuntimeList minstr(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        RuntimeScalar min = args.get(0);
        for (int i = 1; i < args.size(); i++) {
            RuntimeScalar current = args.get(i);
            if (current.toString().compareTo(min.toString()) < 0) {
                min = current;
            }
        }
        return min.getList();
    }

    /**
     * Returns the maximum string value.
     */
    public static RuntimeList maxstr(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        RuntimeScalar max = args.get(0);
        for (int i = 1; i < args.size(); i++) {
            RuntimeScalar current = args.get(i);
            if (current.toString().compareTo(max.toString()) > 0) {
                max = current;
            }
        }
        return max.getList();
    }

    /**
     * Returns the sum of all elements.
     */
    public static RuntimeList sum(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        double sum = 0;
        for (RuntimeScalar arg : args.elements) {
            sum += arg.getDouble();
        }
        return new RuntimeScalar(sum).getList();
    }

    /**
     * Returns the sum of all elements, or 0 if empty.
     */
    public static RuntimeList sum0(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarZero.getList();
        }
        return sum(args, ctx);
    }

    /**
     * Returns the product of all elements.
     */
    public static RuntimeList product(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarOne.getList();
        }

        double product = 1;
        for (RuntimeScalar arg : args.elements) {
            product *= arg.getDouble();
        }
        return new RuntimeScalar(product).getList();
    }

    /**
     * Returns values in random order.
     */
    public static RuntimeList shuffle(RuntimeArray args, int ctx) {
        RuntimeArray shuffled = new RuntimeArray(args.elements);
        Collections.shuffle(shuffled.elements);
        return shuffled.getList();
    }

    /**
     * Randomly select the given number of elements.
     */
    public static RuntimeList sample(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeList();
        }

        int count = args.get(0).getInt();
        RuntimeList values = createSubList(args, 1);

        if (count >= values.size()) {
            RuntimeArray shuffleArgs = new RuntimeArray();
            for (RuntimeBase element : values.elements) {
                shuffleArgs.push(element.scalar());
            }
            return shuffle(shuffleArgs, ctx);
        }

        RuntimeArray result = new RuntimeArray();
        List<RuntimeScalar> shuffled = new ArrayList<>();
        for (RuntimeBase element : values.elements) {
            shuffled.add(element.scalar());
        }
        Collections.shuffle(shuffled);

        for (int i = 0; i < count; i++) {
            result.push(shuffled.get(i));
        }

        return result.getList();
    }

    /**
     * Remove duplicate values using string comparison.
     */
    public static RuntimeList uniq(RuntimeArray args, int ctx) {
        return uniqstr(args, ctx);
    }

    /**
     * Remove duplicate values using integer comparison.
     */
    public static RuntimeList uniqint(RuntimeArray args, int ctx) {
        Set<Long> seen = new LinkedHashSet<>();
        RuntimeArray result = new RuntimeArray();

        for (RuntimeScalar arg : args.elements) {
            Long value = arg.getLong();
            if (seen.add(value)) {
                result.push(new RuntimeScalar(value));
            }
        }

        return ctx == RuntimeContextType.SCALAR ?
                new RuntimeScalar(result.size()).getList() : result.getList();
    }

    /**
     * Remove duplicate values using numerical comparison.
     */
    public static RuntimeList uniqnum(RuntimeArray args, int ctx) {
        Set<Double> seen = new LinkedHashSet<>();
        RuntimeArray result = new RuntimeArray();

        for (RuntimeScalar arg : args.elements) {
            Double value = arg.getDouble();
            if (seen.add(value)) {
                result.push(new RuntimeScalar(value));
            }
        }

        return ctx == RuntimeContextType.SCALAR ?
                new RuntimeScalar(result.size()).getList() : result.getList();
    }

    /**
     * Remove duplicate values using string comparison.
     */
    public static RuntimeList uniqstr(RuntimeArray args, int ctx) {
        Set<String> seen = new LinkedHashSet<>();
        RuntimeArray result = new RuntimeArray();

        for (RuntimeScalar arg : args.elements) {
            String value = arg.toString();
            if (seen.add(value)) {
                result.push(new RuntimeScalar(value));
            }
        }

        return ctx == RuntimeContextType.SCALAR ?
                new RuntimeScalar(result.size()).getList() : result.getList();
    }

    /**
     * Returns the first elements from a list.
     */
    public static RuntimeList head(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeList();
        }

        int size = args.get(0).getInt();
        RuntimeList values = createSubList(args, 1);

        if (size < 0) {
            size = Math.max(0, values.size() + size);
        }

        RuntimeArray result = new RuntimeArray();
        for (int i = 0; i < Math.min(size, values.size()); i++) {
            result.push(values.elements.get(i).scalar());
        }

        return result.getList();
    }

    /**
     * Returns the last elements from a list.
     */
    public static RuntimeList tail(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeList();
        }

        int size = args.get(0).getInt();
        RuntimeList values = createSubList(args, 1);

        RuntimeArray result = new RuntimeArray();
        int start;

        if (size < 0) {
            start = Math.abs(size);
        } else {
            start = Math.max(0, values.size() - size);
        }

        for (int i = start; i < values.size(); i++) {
            result.push(values.elements.get(i).scalar());
        }

        return result.getList();
    }

    /**
     * Returns a list of array references from pairs.
     */
    public static RuntimeList pairs(RuntimeArray args, int ctx) {
        RuntimeArray result = new RuntimeArray();

        for (int i = 0; i < args.size(); i += 2) {
            RuntimeArray pair = new RuntimeArray();
            pair.push(args.get(i));
            if (i + 1 < args.size()) {
                pair.push(args.get(i + 1));
            } else {
                pair.push(scalarUndef);
            }
            result.push(pair.createReference());
        }

        return result.getList();
    }

    /**
     * Flattens pairs back to a list.
     */
    public static RuntimeList unpairs(RuntimeArray args, int ctx) {
        RuntimeArray result = new RuntimeArray();

        for (RuntimeScalar pairRef : args.elements) {
            if (pairRef.type == RuntimeScalarType.ARRAYREFERENCE) {
                RuntimeArray pair = (RuntimeArray) pairRef.value;
                if (pair.size() >= 1) result.push(pair.get(0));
                if (pair.size() >= 2) result.push(pair.get(1));
            }
        }

        return result.getList();
    }

    /**
     * Returns the first values of each pair (keys).
     */
    public static RuntimeList pairkeys(RuntimeArray args, int ctx) {
        RuntimeArray result = new RuntimeArray();

        for (int i = 0; i < args.size(); i += 2) {
            result.push(args.get(i));
        }

        return result.getList();
    }

    /**
     * Returns the second values of each pair (values).
     */
    public static RuntimeList pairvalues(RuntimeArray args, int ctx) {
        RuntimeArray result = new RuntimeArray();

        for (int i = 1; i < args.size(); i += 2) {
            result.push(args.get(i));
        }

        return result.getList();
    }

    /**
     * Maps over pairs with a block.
     */
    public static RuntimeList pairmap(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeList();
        }

        RuntimeScalar codeRef = args.get(0);
        RuntimeList kvlist = createSubList(args, 1);

        RuntimeScalar varA = getGlobalVariable("main::a");
        RuntimeScalar varB = getGlobalVariable("main::b");
        RuntimeScalar saveA = varA.clone();
        RuntimeScalar saveB = varB.clone();

        RuntimeArray result = new RuntimeArray();

        try {
            for (int i = 0; i < kvlist.size(); i += 2) {
                varA.set(kvlist.elements.get(i).scalar());
                varB.set(i + 1 < kvlist.size() ? kvlist.elements.get(i + 1).scalar() : scalarUndef);

                RuntimeList blockResult = RuntimeCode.apply(codeRef, new RuntimeArray(), RuntimeContextType.LIST);
                blockResult.addToArray(result);
            }
        } finally {
            varA.set(saveA);
            varB.set(saveB);
        }

        return ctx == RuntimeContextType.SCALAR ?
                new RuntimeScalar(result.size()).getList() : result.getList();
    }

    /**
     * Filters pairs with a block.
     */
    public static RuntimeList pairgrep(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeList();
        }

        RuntimeScalar codeRef = args.get(0);
        RuntimeList kvlist = createSubList(args, 1);

        RuntimeScalar varA = getGlobalVariable("main::a");
        RuntimeScalar varB = getGlobalVariable("main::b");
        RuntimeScalar saveA = varA.clone();
        RuntimeScalar saveB = varB.clone();

        RuntimeArray result = new RuntimeArray();
        int pairs = 0;

        try {
            for (int i = 0; i < kvlist.size(); i += 2) {
                varA.set(kvlist.elements.get(i).scalar());
                varB.set(i + 1 < kvlist.size() ? kvlist.elements.get(i + 1).scalar() : scalarUndef);

                RuntimeList blockResult = RuntimeCode.apply(codeRef, new RuntimeArray(), RuntimeContextType.SCALAR);
                if (blockResult.getFirst().getBoolean()) {
                    result.push(kvlist.elements.get(i).scalar());
                    if (i + 1 < kvlist.size()) {
                        result.push(kvlist.elements.get(i + 1).scalar());
                    } else {
                        result.push(scalarUndef);
                    }
                    pairs++;
                }
            }
        } finally {
            varA.set(saveA);
            varB.set(saveB);
        }

        return ctx == RuntimeContextType.SCALAR ?
                new RuntimeScalar(pairs).getList() : result.getList();
    }

    /**
     * Returns the first pair where the block returns true.
     */
    public static RuntimeList pairfirst(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return ctx == RuntimeContextType.SCALAR ? scalarFalse.getList() : new RuntimeList();
        }

        RuntimeScalar codeRef = args.get(0);
        RuntimeList kvlist = createSubList(args, 1);

        RuntimeScalar varA = getGlobalVariable("main::a");
        RuntimeScalar varB = getGlobalVariable("main::b");
        RuntimeScalar saveA = varA.clone();
        RuntimeScalar saveB = varB.clone();

        try {
            for (int i = 0; i < kvlist.size(); i += 2) {
                varA.set(kvlist.elements.get(i).scalar());
                varB.set(i + 1 < kvlist.size() ? kvlist.elements.get(i + 1).scalar() : scalarUndef);

                RuntimeList blockResult = RuntimeCode.apply(codeRef, new RuntimeArray(), RuntimeContextType.SCALAR);
                if (blockResult.getFirst().getBoolean()) {
                    if (ctx == RuntimeContextType.SCALAR) {
                        return scalarTrue.getList();
                    } else {
                        RuntimeArray result = new RuntimeArray();
                        result.push(kvlist.elements.get(i).scalar());
                        if (i + 1 < kvlist.size()) {
                            result.push(kvlist.elements.get(i + 1).scalar());
                        } else {
                            result.push(scalarUndef);
                        }
                        return result.getList();
                    }
                }
            }
        } finally {
            varA.set(saveA);
            varB.set(saveB);
        }

        return ctx == RuntimeContextType.SCALAR ? scalarFalse.getList() : new RuntimeList();
    }
}
