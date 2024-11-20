package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

/**
 * The Re class provides functionalities similar to the Perl re module.
 */
public class Re extends PerlModuleBase {

    /**
     * Constructor initializes the module.
     */
    public Re() {
        super("re");
    }

    /**
     * Static initializer to set up the module.
     */
    public static void initialize() {
        Re re = new Re();
        try {
            re.registerMethod("is_regexp", "isRegexp", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing re method: " + e.getMessage());
        }
    }

    /**
     * Method to check if the given argument is a regular expression.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList isRegexp(RuntimeArray args, int ctx) {
        if (args.size()!= 1) {
            throw new IllegalStateException("Bad number of arguments for isRegexp() method");
        }
        return new RuntimeList(
                new RuntimeScalar(args.get(0).type == RuntimeScalarType.REGEX)
        );
    }
}
