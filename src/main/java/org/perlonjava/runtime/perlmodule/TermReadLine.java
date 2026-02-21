package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.getScalarBoolean;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * The TermReadLine class provides functionalities similar to the Perl Term::ReadLine module.
 * This is a cross-platform implementation that works on Ubuntu, Mac, and Windows.
 */
public class TermReadLine extends PerlModuleBase {

    private final List<String> history;
    private final boolean autoHistory;
    private String appName;
    private BufferedReader inputReader;
    private PrintWriter outputWriter;
    private int minLine;
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
            readline.registerMethod("new", "newReadLine", "$;$");
            readline.registerMethod("ReadLine", "getReadLinePackage", "");
            readline.registerMethod("readline", "readLine", "$$");
            readline.registerMethod("addhistory", "addHistory", "$$");
            readline.registerMethod("IN", "getInputHandle", "$");
            readline.registerMethod("OUT", "getOutputHandle", "$");
            readline.registerMethod("MinLine", "minLine", "$;$");
            readline.registerMethod("findConsole", "findConsole", "$");
            readline.registerMethod("Attribs", "getAttribs", "$");
            readline.registerMethod("Features", "getFeatures", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Term::ReadLine method: " + e.getMessage());
        }
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

        // Create a hash to store the instance (similar to DBI's dbh)
        RuntimeHash termHash = new RuntimeHash();
        termHash.put("_instance", new RuntimeScalar(instance));
        termHash.put("appname", new RuntimeScalar(appName));

        // Create blessed reference for Perl compatibility
        RuntimeScalar termRef = ReferenceOperators.bless(termHash.createReference(), new RuntimeScalar("Term::ReadLine"));
        return termRef.getList();
    }

    /**
     * Returns the actual package name that executes the commands.
     */
    public static RuntimeList getReadLinePackage(RuntimeArray args, int ctx) {
        // This can be called as class method or instance method
        return new RuntimeList(new RuntimeScalar("Term::ReadLine::PerlOnJava"));
    }

    /**
     * Gets an input line with readline support. Trailing newline is removed.
     * Returns undef on EOF.
     */
    public static RuntimeList readLine(RuntimeArray args, int ctx) {
        RuntimeHash termHash = args.get(0).hashDeref();
        TermReadLine instance = (TermReadLine) termHash.get("_instance").value;
        String prompt = args.size() > 1 ? args.get(1).toString() : "";

        try {
            // Print prompt to STDOUT using RuntimeIO
            if (!prompt.isEmpty()) {
                RuntimeIO.stdout.write(prompt);
                RuntimeIO.stdout.flush();
            }

            // Flush all file handles to ensure prompt is visible
            RuntimeIO.flushFileHandles();

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
        RuntimeHash termHash = args.get(0).hashDeref();
        TermReadLine instance = (TermReadLine) termHash.get("_instance").value;
        if (args.size() > 1) {
            String line = args.get(1).toString();
            instance.addToHistory(line);
        }
        return new RuntimeList();
    }

    /**
     * Returns the input filehandle or undef.
     */
    public static RuntimeList getInputHandle(RuntimeArray args, int ctx) {
        // Return a Perl glob for STDIN
        return new RuntimeList(new RuntimeScalar(RuntimeIO.stdin));
    }

    /**
     * Returns the output filehandle or undef.
     */
    public static RuntimeList getOutputHandle(RuntimeArray args, int ctx) {
        // Return a Perl glob for STDOUT
        return new RuntimeList(new RuntimeScalar(RuntimeIO.stdout));
    }

    /**
     * Sets or gets the minimum line length for history inclusion.
     */
    public static RuntimeList minLine(RuntimeArray args, int ctx) {
        RuntimeHash termHash = args.get(0).hashDeref();
        TermReadLine instance = (TermReadLine) termHash.get("_instance").value;
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
        // This method can be called as either class method or instance method
        // When called as instance method, args.get(0) will be the object
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
        TermReadLine instance;

        // Handle both instance method and class method calls
        if (args.size() > 1 && args.get(0).toString().equals("Term::ReadLine")) {
            // Called as Term::ReadLine->Attribs($term)
            RuntimeHash termHash = args.get(1).hashDeref();
            instance = (TermReadLine) termHash.get("_instance").value;
        } else {
            // Called as $term->Attribs()
            RuntimeHash termHash = args.get(0).hashDeref();
            instance = (TermReadLine) termHash.get("_instance").value;
        }

        RuntimeHash attribsHash = new RuntimeHash();

        for (Map.Entry<String, Object> entry : instance.attributes.entrySet()) {
            RuntimeScalar value;
            Object attrValue = entry.getValue();

            if (attrValue == null) {
                value = scalarUndef;
            } else if (attrValue instanceof String) {
                value = new RuntimeScalar((String) attrValue);
            } else if (attrValue instanceof Integer) {
                value = new RuntimeScalar((Integer) attrValue);
            } else if (attrValue instanceof Boolean) {
                value = getScalarBoolean((Boolean) attrValue);
            } else {
                value = new RuntimeScalar(attrValue.toString());
            }
            attribsHash.put(entry.getKey(), value);
        }

        return new RuntimeList(attribsHash.createReference());
    }

    /**
     * Returns a reference to a hash with features present in current implementation.
     */
    public static RuntimeList getFeatures(RuntimeArray args, int ctx) {
        RuntimeHash termHash = args.get(0).hashDeref();
        TermReadLine instance = (TermReadLine) termHash.get("_instance").value;
        RuntimeHash featuresHash = new RuntimeHash();

        for (Map.Entry<String, Boolean> entry : instance.features.entrySet()) {
            featuresHash.put(entry.getKey(), getScalarBoolean(entry.getValue()));
        }

        return new RuntimeList(featuresHash.createReference());
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

    private void initializeAttributes() {
        attributes = new HashMap<>();
        attributes.put("appname", appName != null ? appName : "");  // Default to empty string
        attributes.put("minline", minLine);
        attributes.put("autohistory", autoHistory);
        attributes.put("library_version", "PerlOnJava-1.0");
        attributes.put("readline_name", "Term::ReadLine::PerlOnJava");
    }

    // Additional utility methods for history management

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

    private void addToHistory(String line) {
        if (line != null && line.trim().length() >= minLine) {
            history.add(line);
            // Keep history size reasonable (last 1000 entries)
            if (history.size() > 1000) {
                history.remove(0);
            }
        }
    }

    public List<String> getHistory() {
        return new ArrayList<>(history);
    }

    public void setHistory(List<String> newHistory) {
        history.clear();
        if (newHistory != null) {
            history.addAll(newHistory);
        }
    }

    public void clearHistory() {
        history.clear();
    }
}
