package org.perlonjava.runtime.nativ;

import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;

public class PosixLibrary {
    public static final POSIX INSTANCE = POSIXFactory.getNativePOSIX();
}
