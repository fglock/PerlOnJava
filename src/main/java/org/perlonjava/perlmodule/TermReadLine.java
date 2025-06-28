package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeHash;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.RuntimeScalarCache.*;

/**
 * The TermReadLine class provides functionalities similar to the Perl Term::ReadLine module.
 * This is a cross-platform implementation that works on Ubuntu, Mac, and Windows.
 */
public class TermReadLine extends PerlModuleBase {

    private String appName;
    private BufferedReader inputReader;
    private PrintWriter outputWriter;
    private List<String> history;
    private int minLine;
    private boolean autoHistory;
    private Map<String, Object> attributes;
    private Map<String, Boolean> features;

    /**
     * Constructor initializes the module.
     */
    public TermReadLine() {
        super("Term::ReadLine");
        this.history = new ArrayList<>();
        this.minLine = 1;
        this.autoHistory = true;
        initializeAttributes();
        initializeFeatures();
    }

    /**
     * Constructor with application name and optional input/output streams.
     */
    public TermReadLine(String appName, InputStream in, OutputStream out) {
        this();
        this.appName = appName;
        this.inputReader = new BufferedReader(new InputStreamReader(in != null ? in : System.in));
        this.outputWriter = new PrintWriter(out != null ? out : System.out, true);
    }

    /**
     * Static initializer to set up the module.
     */
    public static void initialize() {
        TermReadLine readline = new TermReadLine();

        try {
            readline.registerMethod("new", "newReadLine", "$;$$");
            readline.registerMethod("ReadLine", "getReadLinePackage", "");
            readline.registerMethod("readline", "readLine", "$");
            readline.registerMethod("addhistory", "addHistory", "$");
            readline.registerMethod("IN", "getInputHandle", "");
            readline.registerMethod("OUT", "getOutputHandle", "");
            readline.registerMethod("MinLine", "minLine", ";$");
            readline.registerMethod("findConsole", "findConsole", "");
            readline.registerMethod("Attribs", "getAttribs", "");
            readline.registerMethod("Features", "getFeatures", "");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Term::ReadLine method: " + e.getMessage());
        }
    }

    private void initializeAttributes() {
        attributes = new HashMap<>();
        attributes.put("appname", appName);
        attributes.put("minline", minLine);
        attributes.put("autohistory", autoHistory);
        attributes.put("library_version", "PerlOnJava-1.0");
        attributes.put("readline_name", "Term::ReadLine::PerlOnJava");
    }

    private void initializeFeatures() {
        features = new HashMap<>();
        features.put("appname", true);
        features.put("minline", true);
        features.put("autohistory", true);
        features.put("addhistory", true);
        features.put("attribs", true);
        features.put("setHistory", true);
        features.put("getHistory", true);
    }

    /**
     * Creates a new Term::ReadLine object.
     */
    public static RuntimeList newReadLine(RuntimeArray args, int ctx) {
        String appName = args.size() > 0 ? args.get(0).toString() : "perl";
        InputStream in = System.in;
        OutputStream out = System.out;

        // Handle optional IN and OUT filehandles if provided
        if (args.size() > 1 && args.get(1).getDefinedBoolean()) {
            // TODO: Convert Perl filehandle to Java InputStream
        }
        if (args.size() > 2 && args.get(2).getDefinedBoolean()) {
            // TODO: Convert Perl filehandle to Java OutputStream
        }

        TermReadLine instance = new TermReadLine(appName, in, out);
        return new RuntimeList(new RuntimeScalar(instance));
    }

    /**
     * Returns the actual package name that executes the commands.
     */
    public static RuntimeList getReadLinePackage(RuntimeArray args, int ctx) {
        return new RuntimeList(new RuntimeScalar("Term::ReadLine::PerlOnJava"));
    }

    /**
     * Gets an input line with readline support. Trailing newline is removed.
     * Returns undef on EOF.
     */
    public static RuntimeList readLine(RuntimeArray args, int ctx) {
        TermReadLine instance = (TermReadLine) args.get(0).value;
        String prompt = args.size() > 1 ? args.get(1).toString() : "";

        try {
            if (instance.outputWriter != null && !prompt.isEmpty()) {
                instance.outputWriter.print(prompt);
                instance.outputWriter.flush();
            }

            String line = instance.inputReader.readLine();
            if (line == null) {
                return new RuntimeList(scalarUndef);
            }

            // Auto-add to history if enabled and line meets criteria
            if (instance.autoHistory && line.trim().length() >= instance.minLine) {
                instance.addToHistory(line);
            }

            return new RuntimeList(new RuntimeScalar(line));
        } catch (IOException e) {
            return new RuntimeList(scalarUndef);
        }
    }

    /**
     * Adds a line to the history.
     */
    public static RuntimeList addHistory(RuntimeArray args, int ctx) {
        TermReadLine instance = (TermReadLine) args.get(0).value;
        if (args.size() > 1) {
            String line = args.get(1).toString();
            instance.addToHistory(line);
        }
        return new RuntimeList();
    }

    private void addToHistory(String line) {
        if (line != null && line.trim().length() >= minLine) {
            history.add(line);
            // Keep history size reasonable (last 1000 entries)
            if (history.size() > 1000) {
                history.remove(0);
            }
        }
    }

    /**
     * Returns the input filehandle or undef.
     */
    public static RuntimeList getInputHandle(RuntimeArray args, int ctx) {
        // In a real implementation, this would return a Perl glob for STDIN
        // For now, return undef since we can't easily represent Java streams as Perl globs
        return new RuntimeList(scalarUndef);
    }

    /**
     * Returns the output filehandle or undef.
     */
    public static RuntimeList getOutputHandle(RuntimeArray args, int ctx) {
        // In a real implementation, this would return a Perl glob for STDOUT
        // For now, return undef since we can't easily represent Java streams as Perl globs
        return new RuntimeList(scalarUndef);
    }

    /**
     * Sets or gets the minimum line length for history inclusion.
     */
    public static RuntimeList minLine(RuntimeArray args, int ctx) {
        TermReadLine instance = (TermReadLine) args.get(0).value;
        int oldValue = instance.minLine;

        if (args.size() > 1 && args.get(1).getDefinedBoolean()) {
            instance.minLine = args.get(1).getInt();
            instance.attributes.put("minline", instance.minLine);
        }

        return new RuntimeList(new RuntimeScalar(oldValue));
    }

    /**
     * Finds appropriate console input/output file names.
     */
    public static RuntimeList findConsole(RuntimeArray args, int ctx) {
        String os = System.getProperty("os.name").toLowerCase();
        String inputFile, outputFile;

        if (os.contains("win")) {
            // Windows
            inputFile = "CONIN$";
            outputFile = "CONOUT$";
        } else {
            // Unix-like systems (Linux, macOS)
            inputFile = "/dev/tty";
            outputFile = "/dev/tty";
        }

        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(inputFile));
        result.add(new RuntimeScalar(outputFile));
        return result;
    }

    /**
     * Returns a reference to a hash describing internal configuration.
     */
    public static RuntimeList getAttribs(RuntimeArray args, int ctx) {
        TermReadLine instance = (TermReadLine) args.get(0).value;
        RuntimeHash attribsHash = new RuntimeHash();

        for (Map.Entry<String, Object> entry : instance.attributes.entrySet()) {
            RuntimeScalar value;
            if (entry.getValue() instanceof String) {
                value = new RuntimeScalar((String) entry.getValue());
            } else if (entry.getValue() instanceof Integer) {
                value = new RuntimeScalar((Integer) entry.getValue());
            } else if (entry.getValue() instanceof Boolean) {
                value = getScalarBoolean((Boolean) entry.getValue());
            } else {
                value = new RuntimeScalar(entry.getValue().toString());
            }
            attribsHash.put(entry.getKey(), value);
        }

        return new RuntimeList(new RuntimeScalar(attribsHash));
    }

    /**
     * Returns a reference to a hash with features present in current implementation.
     */
    public static RuntimeList getFeatures(RuntimeArray args, int ctx) {
        TermReadLine instance = (TermReadLine) args.get(0).value;
        RuntimeHash featuresHash = new RuntimeHash();

        for (Map.Entry<String, Boolean> entry : instance.features.entrySet()) {
            featuresHash.put(entry.getKey(), getScalarBoolean(entry.getValue()));
        }

        return new RuntimeList(new RuntimeScalar(featuresHash));
    }

    // Additional utility methods for history management

    public List<String> getHistory() {
        return new ArrayList<>(history);
    }

    public void clearHistory() {
        history.clear();
    }

    public void setHistory(List<String> newHistory) {
        history.clear();
        if (newHistory != null) {
            history.addAll(newHistory);
        }
    }

    // Cross-platform console detection
    private static boolean isConsoleAvailable() {
        return System.console() != null;
    }

    // Method to check if we're running in an interactive terminal
    private static boolean isInteractive() {
        String term = System.getenv("TERM");
        return term != null && !term.equals("dumb") && isConsoleAvailable();
    }
}