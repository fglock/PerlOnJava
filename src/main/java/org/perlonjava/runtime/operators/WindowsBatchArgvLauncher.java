package org.perlonjava.runtime.operators;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Internal transport for batch-file arguments that cmd.exe cannot quote
 * losslessly, including physical newlines and embedded double quotes.
 *
 * <p>The outer Java process receives URL-safe base64, so no cmd.exe parser can
 * consume part of an argument. The decoded values are installed in the child
 * environment and expanded with delayed expansion after cmd has parsed the
 * command structure. Because the target batch inherits delayed expansion, a
 * literal {@code !} in its path or argv cannot be transported safely through
 * its later {@code %1}/{@code %*} expansion; reject that case explicitly
 * instead of silently changing the child's arguments.</p>
 */
public final class WindowsBatchArgvLauncher {
    private WindowsBatchArgvLauncher() {
    }

    public static void main(String[] encoded) throws Exception {
        if (encoded.length == 0) {
            System.err.println("Windows batch launcher requires a script path");
            System.exit(255);
            return;
        }

        Base64.Decoder decoder = Base64.getUrlDecoder();
        String script = decode(decoder, encoded[0]);
        rejectDelayedExpansionHazard(script, "batch script path");
        StringBuilder command = new StringBuilder("\"\"!PERLONJAVA_BATCH_SCRIPT!\"");
        ProcessBuilder builder = new ProcessBuilder();
        builder.environment().put("PERLONJAVA_BATCH_SCRIPT", script);
        for (int i = 1; i < encoded.length; i++) {
            String name = "PERLONJAVA_BATCH_ARG_" + (i - 1);
            String argument = decode(decoder, encoded[i]);
            rejectDelayedExpansionHazard(argument, "batch argument " + (i - 1));
            builder.environment().put(name, argument);
            command.append(" \"!").append(name).append("!\"");
        }
        command.append('\"');

        builder.command("cmd.exe", "/v:on", "/x", "/d", "/s", "/c",
                command.toString());
        builder.inheritIO();
        System.exit(builder.start().waitFor());
    }

    private static String decode(Base64.Decoder decoder, String encoded) {
        return new String(decoder.decode(encoded), StandardCharsets.UTF_8);
    }

    static void rejectDelayedExpansionHazard(String value, String role) {
        if (value.indexOf('!') >= 0) {
            throw new IllegalArgumentException(
                    role + " contains !, which cmd.exe delayed expansion cannot preserve");
        }
    }
}
