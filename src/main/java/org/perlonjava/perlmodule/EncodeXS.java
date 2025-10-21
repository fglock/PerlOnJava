package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * The EncodeXS module provides encoding object methods.
 * This is loaded by Encode::XS via XSLoader.
 */
public class EncodeXS extends PerlModuleBase {

    /**
     * Constructor for EncodeXS.
     */
    public EncodeXS() {
        super("Encode::XS", true);
    }

    /**
     * Static initializer to set up the EncodeXS module.
     */
    public static void initialize() {
        EncodeXS encodeXS = new EncodeXS();
        try {
            encodeXS.registerMethod("decode", null);
            encodeXS.registerMethod("encode", null);
            encodeXS.registerMethod("name", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Encode::XS method: " + e.getMessage());
        }
    }

    /**
     * decode($self, $octets, $check)
     * Decodes octets using the encoding stored in $self->{Name}
     */
    public static RuntimeList decode(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarUndef.getList();
        }
        
        // args[0] is $self (blessed hash), args[1] is $octets
        RuntimeScalar self = args.get(0);
        RuntimeScalar octets = args.get(1);
        
        // Get the encoding name from the blessed object
        String charsetName = self.hashDerefGet(new RuntimeScalar("Name")).toString();
        if (charsetName.isEmpty()) {
            charsetName = self.hashDerefGet(new RuntimeScalar("name")).toString();
        }
        if (charsetName.isEmpty()) {
            charsetName = "UTF-8";
        }
        
        // Call Encode::decode with the charset name
        return Encode.decode(new RuntimeArray(new RuntimeScalar(charsetName), octets), ctx);
    }

    /**
     * encode($self, $string, $check)
     * Encodes string using the encoding stored in $self->{Name}
     */
    public static RuntimeList encode(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarUndef.getList();
        }
        
        // args[0] is $self (blessed hash), args[1] is $string
        RuntimeScalar self = args.get(0);
        RuntimeScalar string = args.get(1);
        
        // Get the encoding name from the blessed object
        String charsetName = self.hashDerefGet(new RuntimeScalar("Name")).toString();
        if (charsetName.isEmpty()) {
            charsetName = self.hashDerefGet(new RuntimeScalar("name")).toString();
        }
        if (charsetName.isEmpty()) {
            charsetName = "UTF-8";
        }
        
        // Call Encode::encode with the charset name
        return Encode.encode(new RuntimeArray(new RuntimeScalar(charsetName), string), ctx);
    }

    /**
     * name($self)
     * Returns the name of the encoding
     */
    public static RuntimeList name(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }
        
        RuntimeScalar self = args.get(0);
        RuntimeScalar name = self.hashDerefGet(new RuntimeScalar("Name"));
        if (name.type == org.perlonjava.runtime.RuntimeScalarType.UNDEF) {
            name = self.hashDerefGet(new RuntimeScalar("name"));
        }
        return name.getList();
    }
}

