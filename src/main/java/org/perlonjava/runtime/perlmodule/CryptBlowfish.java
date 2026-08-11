package org.perlonjava.runtime.perlmodule;

import org.bouncycastle.crypto.engines.BlowfishEngine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.perlonjava.frontend.parser.StringParser;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Crypt::Blowfish's two XS functions, backed by BouncyCastle. */
public final class CryptBlowfish extends PerlModuleBase {
    private static final String MODULE = "Crypt::Blowfish";

    private record KeySchedule(byte[] key) {
        private KeySchedule {
            key = Arrays.copyOf(key, key.length);
        }
    }

    public CryptBlowfish() {
        super(MODULE, false);
    }

    public static void initialize() {
        CryptBlowfish module = new CryptBlowfish();
        try {
            module.registerMethod("init", null);
            module.registerMethod("crypt", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize " + MODULE, e);
        }
    }

    public static RuntimeList init(RuntimeArray args, int ctx) {
        if (args.isEmpty()) throw new PerlCompilerException("Invalid length key");
        String keyString = args.get(0).toString();
        StringParser.assertNoWideCharacters(keyString, "Crypt::Blowfish::init");
        byte[] key = keyString.getBytes(StandardCharsets.ISO_8859_1);
        if (key.length < 8 || key.length > 56) {
            throw new PerlCompilerException("Invalid length key");
        }
        return new RuntimeScalar(new KeySchedule(key)).getList();
    }

    public static RuntimeList crypt(RuntimeArray args, int ctx) {
        if (args.size() < 4) {
            throw new PerlCompilerException("Usage: Crypt::Blowfish::crypt(input, output, key, direction)");
        }
        String inputString = args.get(0).toString();
        StringParser.assertNoWideCharacters(inputString, "Crypt::Blowfish::crypt");
        byte[] input = inputString.getBytes(StandardCharsets.ISO_8859_1);
        if (input.length != 8) throw new PerlCompilerException("input must be 8 bytes long");

        RuntimeScalar scheduleArg = args.get(2);
        if (scheduleArg.type != RuntimeScalarType.JAVAOBJECT
                || !(scheduleArg.value instanceof KeySchedule schedule)) {
            throw new PerlCompilerException("Invalid Blowfish key schedule");
        }

        boolean encrypt = args.get(3).getInt() == 0;
        BlowfishEngine engine = new BlowfishEngine();
        engine.init(encrypt, new KeyParameter(schedule.key()));
        byte[] output = new byte[8];
        engine.processBlock(input, 0, output, 0);
        RuntimeScalar result = new RuntimeScalar(output);
        args.get(1).set(result);
        return result.getList();
    }
}
