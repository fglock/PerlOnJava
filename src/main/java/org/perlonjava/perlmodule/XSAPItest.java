package org.perlonjava.perlmodule;

import org.perlonjava.operators.pack.PointerPackHandler;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

public class XSAPItest extends PerlModuleBase {

    public XSAPItest() {
        super("XS::APItest", true);
    }

    public static void initialize() {
        XSAPItest apiTest = new XSAPItest();
        try {
            apiTest.registerMethod("modify_pv", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing XS::APItest method: " + e.getMessage());
        }
    }

    public static RuntimeList modify_pv(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalArgumentException("XS::APItest::modify_pv requires (ptr, len)");
        }

        long ptrLong = args.get(0).getLong();
        int ptr = (int) ptrLong;
        int len = args.get(1).getInt();

        PointerPackHandler.modifyPointer(ptr, len);
        return scalarTrue.getList();
    }
}
