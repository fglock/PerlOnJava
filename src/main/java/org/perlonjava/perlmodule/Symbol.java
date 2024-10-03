package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import java.lang.reflect.Method;

import static org.perlonjava.runtime.GlobalContext.getGlobalCodeRef;
import static org.perlonjava.runtime.GlobalContext.getGlobalHash;

public class Symbol {

    public static void initialize() {
        // Initialize Symbol class

        // Set %INC
        getGlobalHash("main::INC").put("Symbol.pm", new RuntimeScalar("Symbol.pm"));

        try {
            // load Symbol methods into Perl namespace
            Class<?> clazz = Symbol.class;
            RuntimeScalar instance = new RuntimeScalar();
            Method mm;

            mm = clazz.getMethod("qualify_to_ref", RuntimeArray.class, int.class);
            getGlobalCodeRef("Symbol::qualify_to_ref").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$;$")));

            mm = clazz.getMethod("qualify", RuntimeArray.class, int.class);
            getGlobalCodeRef("Symbol::qualify").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, "$;$")));

        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Symbol method: " + e.getMessage());
        }
    }

//    "Symbol::qualify" turns unqualified symbol names into qualified variable
//    names (e.g. "myvar" -> "MyPackage::myvar"). If it is given a second
//    parameter, "qualify" uses it as the default package; otherwise, it uses
//    the package of its caller. Regardless, global variable names (e.g.
//    "STDOUT", "ENV", "SIG") are always qualified with "main::".
//
//    Qualification applies only to symbol names (strings). References are
//    left unchanged under the assumption that they are glob references, which
//    are qualified by their nature.
    public static RuntimeList qualify(RuntimeArray args, int ctx) {
        if (args.size() < 1 || args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for qualify()");
        }
        RuntimeScalar object = args.get(0);
        RuntimeScalar packageName = null;
        if (args.size() > 1) {
            packageName = args.get(1);
        } else {
            // XXX TODO - default to caller()
            packageName = new RuntimeScalar("main");
        }
        RuntimeScalar result;
        // System.out.println("qualify " + object + " :: " + packageName + " type:" + object.type);
        if (object.type != RuntimeScalarType.STRING) {
            result = object;
        } else {
            // System.out.println("qualify normalizeVariableName");
            result = new RuntimeScalar(NameNormalizer.normalizeVariableName(object.toString(), packageName.toString()));
        }
        RuntimeList list = new RuntimeList();
        list.elements.add(result);
        return list;
    }

//    "Symbol::qualify_to_ref" is just like "Symbol::qualify" except that it
//    returns a glob ref rather than a symbol name, so you can use the result
//    even if "use strict 'refs'" is in effect.
    public static RuntimeList qualify_to_ref(RuntimeArray args, int ctx) {
        if (args.size() < 1 || args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for qualify_to_ref()");
        }
        RuntimeScalar object = qualify(args, ctx).scalar();
        RuntimeScalar result;
        if (object.type != RuntimeScalarType.STRING) {
            result = object;
        } else {
            // System.out.println("qualify_to_ref");
            result = new RuntimeScalar().set(new RuntimeGlob(object.toString()));
        }
        // System.out.println("qualify_to_ref returns " + result.type);
        RuntimeList list = new RuntimeList();
        list.elements.add(result);
        return list;
    }

}
