package org.perlonjava.runtime.operators;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

import org.perlonjava.app.cli.Main;

/**
 * Internal transport for batch-file arguments that cmd.exe cannot quote
 * losslessly, including physical newlines and embedded double quotes.
 *
 * <p>The outer Java process receives URL-safe base64, so no cmd.exe parser can
 * consume part of an argument. Ordinary batch targets receive the decoded
 * values through delayed environment expansion after cmd has parsed the
 * command structure. A literal {@code !} cannot survive that expansion, so
 * those targets reject it explicitly. The {@code jperl.bat} target is
 * different: it dispatches straight to {@link Main} and never invokes
 * {@code cmd.exe}, so it preserves literal exclamation marks losslessly.</p>
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

        List<String> decoded = decodeArguments(encoded);
        String script = decoded.getFirst();
        List<String> arguments = decoded.subList(1, decoded.size());
        if (isJperlBatch(script)) {
            invokeJperl(arguments, Main::main);
            return;
        }

        rejectDelayedExpansionHazard(script, "batch script path");
        for (int i = 0; i < arguments.size(); i++) {
            rejectDelayedExpansionHazard(arguments.get(i), "batch argument " + i);
        }

        ProcessBuilder builder = new ProcessBuilder();
        builder.environment().put("PERLONJAVA_BATCH_SCRIPT", script);
        StringBuilder command = new StringBuilder("\"\"!PERLONJAVA_BATCH_SCRIPT!\"");
        for (int i = 0; i < arguments.size(); i++) {
            String name = "PERLONJAVA_BATCH_ARG_" + i;
            builder.environment().put(name, arguments.get(i));
            command.append(" \"!").append(name).append("!\"");
        }
        command.append('\"');
        builder.command("cmd.exe", "/v:on", "/x", "/d", "/s", "/c",
                command.toString());
        builder.inheritIO();
        System.exit(builder.start().waitFor());
    }

    static List<String> decodeArguments(String[] encoded) {
        Base64.Decoder decoder = Base64.getUrlDecoder();
        List<String> decoded = new ArrayList<>(encoded.length);
        for (int i = 0; i < encoded.length; i++) {
            String value = decode(decoder, encoded[i]);
            decoded.add(value);
        }
        return decoded;
    }

    static boolean isJperlBatch(String script) {
        String normalized = script.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        String executableName = separator >= 0
                ? normalized.substring(separator + 1) : normalized;
        return "jperl.bat".equalsIgnoreCase(executableName);
    }

    static void invokeJperl(List<String> arguments, Consumer<String[]> entryPoint) {
        entryPoint.accept(arguments.toArray(String[]::new));
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
