package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import java.lang.reflect.Method;

import static org.perlonjava.runtime.GlobalContext.*;

public class Parent {

    public static void initialize() {
        // Initialize `parent` class

        // Set %INC
        getGlobalHash("main::INC").put("parent.pm", new RuntimeScalar("parent.pm"));

        try {
            // load parent methods into Perl namespace
            Class<?> clazz = Parent.class;
            RuntimeScalar instance = new RuntimeScalar();
            Method mm;

            mm = clazz.getMethod("importParent", RuntimeArray.class, int.class);
            getGlobalCodeRef("parent::import").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, null)));

        } catch (Exception e) {
            System.err.println("Warning: Failed to initialize parent: " + e.getMessage());
        }
    }

    public static RuntimeList importParent(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new PerlCompilerException("Not enough arguments for parent::import");
        }

        RuntimeScalar packageScalar = args.shift();
        String packageName = packageScalar.scalar().toString();

        RuntimeList callerList = RuntimeScalar.caller(new RuntimeList(), RuntimeContextType.SCALAR);
        String inheritor = callerList.scalar().toString();

        boolean noRequire = false;
        if (args.size() > 0 && args.get(0).toString().equals("-norequire")) {
            noRequire = true;
            args.shift();
        }

        for (RuntimeScalar parentClass : args.elements) {
            String parentClassName = parentClass.toString();

            if (!noRequire) {
                String filename = parentClassName.replace("::", "/").replace("'", "/") + ".pm";
                RuntimeScalar ret = new RuntimeScalar(filename).require();
            }

            // Add the parent class to the @ISA array of the inheritor
            RuntimeArray isa = getGlobalArray(inheritor + "::ISA");
            isa.push(new RuntimeScalar(parentClassName));
        }

        return new RuntimeList();
    }
}

