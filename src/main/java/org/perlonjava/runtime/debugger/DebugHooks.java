package org.perlonjava.runtime.debugger;

import org.perlonjava.backend.bytecode.EvalStringHandler;
import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.backend.bytecode.InterpreterState;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Debug hooks called by the DEBUG opcode in the interpreter.
 * <p>
 * All debugger logic is centralized here:
 * - Breakpoint checking
 * - Command input and parsing
 * - Step/next/continue control
 * <p>
 * This design keeps the interpreter loop clean and allows
 * the same hooks to be used by the JVM backend in the future.
 */
public class DebugHooks {

    private static final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
    
    // Command counter for prompt (1-indexed like Perl)
    private static int commandCounter = 1;
    
    // Current execution context for expression evaluation
    private static InterpretedCode currentCode;
    private static RuntimeBase[] currentRegisters;
    
    // Flag to track if PERL5DB was set (user wants custom debugger)
    private static boolean hasCustomDebugger = false;
    
    // Flag to track if we've already tried to execute PERL5DB
    private static boolean perl5dbExecuted = false;

    /**
     * Main debug hook called by DEBUG opcode.
     * Checks if we should stop and handles debugger interaction.
     *
     * @param filename  Current source filename
     * @param line      Current line number (1-based)
     * @param code      Current InterpretedCode (for expression evaluation)
     * @param registers Current register array (for variable access)
     */
    public static void debug(String filename, int line, InterpretedCode code, RuntimeBase[] registers) {
        // Execute PERL5DB on first call (defines user's DB::DB if set)
        if (!perl5dbExecuted) {
            perl5dbExecuted = true;
            executePERL5DB();
        }
        
        // Store context for expression evaluation
        currentCode = code;
        currentRegisters = registers;
        
        // Sync from Perl $DB::single variable to DebugState
        syncFromPerlVariables();
        
        // Sync %DB::sub with any newly compiled subroutines
        syncDbSub();
        
        // Update current location
        DebugState.currentFile = filename;
        DebugState.currentLine = line;
        
        // Update Perl debug variables
        GlobalVariable.getGlobalVariable("DB::filename").set(filename);
        GlobalVariable.getGlobalVariable("DB::line").set(line);

        // Check if we should stop
        if (!DebugState.shouldStop(filename, line)) {
            return;
        }

        // Check for quit flag
        if (DebugState.quit) {
            System.exit(0);
        }

        // Populate @DB::args with current frame's arguments
        RuntimeArray dbArgs = GlobalVariable.getGlobalArray("DB::args");
        RuntimeArray frameArgs = DebugState.getArgsForFrame(0);
        if (frameArgs != null) {
            dbArgs.setFromList(frameArgs.getList());
        } else {
            dbArgs.setFromList(new RuntimeList());
        }

        // If user has defined custom DB::DB, call it instead of our interactive debugger
        if (hasCustomDebugger) {
            callUserDbDb();
            return;
        }

        // Get source line for display
        String sourceLine = DebugState.getSourceLine(filename, line);
        
        // Get current package name
        String packageName = InterpreterState.currentPackage.get().toString();

        // Display current location (format matches Perl: "package::(file:line):\nline:  code")
        System.out.printf("%s::(%s:%d):%n", packageName, filename, line);
        System.out.printf("%d:  %s%n", line, sourceLine.trim());

        // Enter command loop
        commandLoop();
    }
    
    /**
     * Execute PERL5DB environment variable if set.
     * This allows users to define custom DB::DB subroutines.
     */
    private static void executePERL5DB() {
        String perl5db = System.getenv("PERL5DB");
        if (perl5db == null || perl5db.isEmpty()) {
            return;
        }
        
        hasCustomDebugger = true;
        
        // Execute PERL5DB code in DB package context
        try {
            // Temporarily disable debug mode to avoid infinite recursion
            boolean savedDebugMode = DebugState.debugMode;
            DebugState.debugMode = false;
            
            // Wrap in package DB to ensure subs are defined there
            String wrappedCode = "package DB; " + perl5db;
            EvalStringHandler.evalString(wrappedCode, new RuntimeBase[0], "<DB>", 1);
            
            DebugState.debugMode = savedDebugMode;
        } catch (Exception e) {
            // If PERL5DB execution fails, fall back to interactive debugger
            hasCustomDebugger = false;
            System.err.println("Warning: Error executing PERL5DB: " + e.getMessage());
        }
    }
    
    /**
     * Call user-defined DB::DB subroutine.
     */
    private static void callUserDbDb() {
        try {
            RuntimeScalar dbDb = GlobalVariable.getGlobalCodeRef("DB::DB");
            if (dbDb.getDefinedBoolean()) {
                RuntimeCode.apply(dbDb, new RuntimeArray(), RuntimeContextType.VOID);
            }
        } catch (Exception e) {
            // Ignore errors in user's DB::DB - Perl does this too
        }
    }

    /**
     * Command loop - reads and executes debugger commands.
     * Returns when execution should continue.
     */
    private static void commandLoop() {
        while (true) {
            System.out.printf("  DB<%d> ", commandCounter);
            System.out.flush();

            String input;
            try {
                input = reader.readLine();
            } catch (IOException e) {
                // EOF or error - quit
                DebugState.quit = true;
                return;
            }

            if (input == null) {
                // EOF - quit
                DebugState.quit = true;
                return;
            }

            input = input.trim();

            // Empty input or just Enter - repeat last command (default: step)
            if (input.isEmpty()) {
                input = "n";  // Default to next/step
            }

            // Parse and execute command
            if (executeCommand(input)) {
                commandCounter++;  // Increment after command that resumes execution
                return;  // Command indicates we should resume execution
            }
        }
    }

    /**
     * Execute a debugger command.
     *
     * @param cmd The command string
     * @return true if execution should resume, false to stay in command loop
     */
    private static boolean executeCommand(String cmd) {
        // Get just the first character/word for command dispatch
        char cmdChar = cmd.charAt(0);
        String args = cmd.length() > 1 ? cmd.substring(1).trim() : "";

        switch (cmdChar) {
            case 'n':  // next - step over
                return handleNext(args);

            case 's':  // step - step into
                return handleStep(args);

            case 'r':  // return - step out
                return handleReturn(args);

            case 'c':  // continue
                return handleContinue(args);

            case 'q':  // quit
                return handleQuit(args);

            case 'l':  // list source
                handleList(args);
                return false;

            case '.':  // show current line
                handleShowCurrent();
                return false;

            case 'T':  // stack trace
                handleStackTrace();
                return false;

            case 'h':  // help
            case '?':
                handleHelp();
                return false;

            case 'b':  // breakpoint
                handleBreakpoint(args);
                return false;

            case 'B':  // delete breakpoint
                handleDeleteBreakpoint(args);
                return false;

            case 'L':  // list breakpoints
                handleListBreakpoints();
                return false;

            case 'p':  // print expression
                handlePrint(args);
                return false;

            case 'x':  // dump expression
                handleDump(args);
                return false;

            default:
                // Unknown command - could be Perl expression to evaluate
                // For now, just show help
                System.out.println("Unknown command: " + cmd);
                System.out.println("Type 'h' for help");
                return false;
        }
    }

    /**
     * Handle 'n' (next) command - step over subroutine calls.
     */
    private static boolean handleNext(String args) {
        // Set step-over depth to current depth
        // DEBUG hook will skip while callDepth > stepOverDepth
        DebugState.stepOverDepth = DebugState.callDepth;
        DebugState.stepOutDepth = -1;
        DebugState.single = true;
        syncToPerlVariables();
        return true;
    }

    /**
     * Handle 's' (step) command - step into subroutine calls.
     */
    private static boolean handleStep(String args) {
        // Disable step-over/step-out, enable single-step
        DebugState.stepOverDepth = -1;
        DebugState.stepOutDepth = -1;
        DebugState.single = true;
        syncToPerlVariables();
        return true;
    }

    /**
     * Handle 'r' (return) command - step out of current subroutine.
     */
    private static boolean handleReturn(String args) {
        // Set step-out depth to current depth
        // DEBUG hook will skip until callDepth < stepOutDepth
        DebugState.stepOutDepth = DebugState.callDepth;
        DebugState.stepOverDepth = -1;
        DebugState.single = true;
        syncToPerlVariables();
        return true;
    }

    /**
     * Handle 'c' (continue) command - run until breakpoint or end.
     */
    private static boolean handleContinue(String args) {
        // Disable single-step, step-over, step-out
        DebugState.single = false;
        DebugState.stepOverDepth = -1;
        DebugState.stepOutDepth = -1;

        // If argument provided, it's a line number for one-time breakpoint
        if (!args.isEmpty()) {
            try {
                int targetLine = Integer.parseInt(args);
                String key = DebugState.currentFile + ":" + targetLine;
                // Use one-time breakpoint so it's removed after being hit
                DebugState.oneTimeBreakpoints.add(key);
            } catch (NumberFormatException e) {
                System.out.println("Invalid line number: " + args);
                return false;
            }
        }

        syncToPerlVariables();
        return true;
    }

    /**
     * Handle 'q' (quit) command - exit the program.
     */
    private static boolean handleQuit(String args) {
        System.out.println("Debugger exiting.");
        System.exit(0);
        return true;  // Never reached
    }

    /**
     * Handle 'l' (list) command - show source code.
     */
    private static void handleList(String args) {
        String file = DebugState.currentFile;
        int centerLine = DebugState.currentLine;
        int range = 5;  // Show 5 lines before and after

        // Parse args for line range
        if (!args.isEmpty()) {
            try {
                if (args.contains("-")) {
                    String[] parts = args.split("-");
                    int start = Integer.parseInt(parts[0].trim());
                    int end = Integer.parseInt(parts[1].trim());
                    showLines(file, start, end);
                    return;
                } else {
                    centerLine = Integer.parseInt(args);
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid line specification: " + args);
                return;
            }
        }

        int start = Math.max(1, centerLine - range);
        int end = centerLine + range;
        showLines(file, start, end);
    }

    /**
     * Show source lines from file.
     */
    private static void showLines(String file, int start, int end) {
        String[] lines = DebugState.sourceLines.get(file);
        if (lines == null) {
            System.out.println("No source available for: " + file);
            return;
        }

        end = Math.min(end, lines.length - 1);
        for (int i = start; i <= end; i++) {
            String marker = (i == DebugState.currentLine) ? "==>" : "   ";
            String bpMarker = DebugState.breakpoints.contains(file + ":" + i) ? "b" : " ";
            String line = (i < lines.length && lines[i] != null) ? lines[i] : "";
            System.out.printf("%s%s%4d:\t%s%n", marker, bpMarker, i, line);
        }
    }

    /**
     * Handle '.' command - show current line.
     */
    private static void handleShowCurrent() {
        String sourceLine = DebugState.getSourceLine(DebugState.currentFile, DebugState.currentLine);
        System.out.printf("%s:%d:\t%s%n",
                DebugState.currentFile,
                DebugState.currentLine,
                sourceLine);
    }

    /**
     * Handle 'b' (breakpoint) command - set a breakpoint.
     */
    private static void handleBreakpoint(String args) {
        int line;
        String file = DebugState.currentFile;

        if (args.isEmpty()) {
            // Breakpoint at current line
            line = DebugState.currentLine;
        } else if (args.contains(":")) {
            // file:line format
            String[] parts = args.split(":", 2);
            file = parts[0];
            try {
                line = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                System.out.println("Invalid line number: " + parts[1]);
                return;
            }
        } else {
            // Just line number
            try {
                line = Integer.parseInt(args.trim());
            } catch (NumberFormatException e) {
                System.out.println("Invalid line number: " + args);
                return;
            }
        }

        String key = file + ":" + line;
        DebugState.breakpoints.add(key);
        System.out.println("Breakpoint set at " + key);
    }

    /**
     * Handle 'B' (delete breakpoint) command.
     */
    private static void handleDeleteBreakpoint(String args) {
        if (args.equals("*")) {
            // Delete all breakpoints
            DebugState.breakpoints.clear();
            System.out.println("All breakpoints deleted");
            return;
        }

        int line;
        String file = DebugState.currentFile;

        if (args.isEmpty()) {
            line = DebugState.currentLine;
        } else if (args.contains(":")) {
            String[] parts = args.split(":", 2);
            file = parts[0];
            try {
                line = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                System.out.println("Invalid line number: " + parts[1]);
                return;
            }
        } else {
            try {
                line = Integer.parseInt(args.trim());
            } catch (NumberFormatException e) {
                System.out.println("Invalid line number: " + args);
                return;
            }
        }

        String key = file + ":" + line;
        if (DebugState.breakpoints.remove(key)) {
            System.out.println("Breakpoint deleted at " + key);
        } else {
            System.out.println("No breakpoint at " + key);
        }
    }

    /**
     * Handle 'L' (list breakpoints) command.
     */
    private static void handleListBreakpoints() {
        if (DebugState.breakpoints.isEmpty()) {
            System.out.println("No breakpoints set");
            return;
        }

        System.out.println("Breakpoints:");
        for (String bp : DebugState.breakpoints) {
            System.out.println("  " + bp);
        }
    }

    /**
     * Handle 'T' (stack trace) command - show call stack.
     */
    private static void handleStackTrace() {
        Throwable t = new Throwable();
        java.util.ArrayList<java.util.ArrayList<String>> stackTrace =
                org.perlonjava.runtime.runtimetypes.ExceptionFormatter.formatException(t);

        if (stackTrace.isEmpty()) {
            System.out.println("(no stack trace available)");
            return;
        }

        // Skip the first frame (handleStackTrace itself)
        for (int i = 1; i < stackTrace.size(); i++) {
            java.util.ArrayList<String> frame = stackTrace.get(i);
            String pkg = frame.get(0);
            String file = frame.get(1);
            String line = frame.get(2);
            String sub = (frame.size() > 3 && frame.get(3) != null) ? frame.get(3) : "(main)";

            // Format: . = pkg::sub() called from file line N
            if (i == 1) {
                System.out.printf(". = %s::%s() called from %s line %s%n", pkg, sub, file, line);
            } else {
                System.out.printf("@ = %s::%s() called from %s line %s%n", pkg, sub, file, line);
            }
        }
    }

    /**
     * Handle 'h' (help) command.
     */
    private static void handleHelp() {
        System.out.println("Debugger commands:");
        System.out.println("  n          Next (step over) - execute until next statement");
        System.out.println("  s          Step into - step into subroutine calls");
        System.out.println("  r          Return - step out of current subroutine");
        System.out.println("  c [line]   Continue - run until breakpoint or line");
        System.out.println("  q          Quit - exit the debugger");
        System.out.println("  T          Stack trace - show call stack");
        System.out.println("  l [range]  List source (e.g., 'l 10-20' or 'l 15')");
        System.out.println("  .          Show current line");
        System.out.println("  b [line]   Set breakpoint (e.g., 'b 10' or 'b file.pl:10')");
        System.out.println("  B [line]   Delete breakpoint ('B *' deletes all)");
        System.out.println("  L          List all breakpoints");
        System.out.println("  p expr     Print expression result");
        System.out.println("  x expr     Dump expression (structured output)");
        System.out.println("  h or ?     Show this help");
        System.out.println("");
        System.out.println("Press Enter to repeat last command (default: n)");
    }

    /**
     * Handle 'p' (print) command - evaluate and print expression.
     */
    private static void handlePrint(String expr) {
        if (expr.isEmpty()) {
            System.out.println("Usage: p <expression>");
            return;
        }

        // Temporarily disable debug mode during expression evaluation
        boolean savedDebugMode = DebugState.debugMode;
        DebugState.debugMode = false;
        
        try {
            // Evaluate the expression using eval in scalar context
            RuntimeScalar result = EvalStringHandler.evalString(
                    expr,
                    currentCode,
                    currentRegisters,
                    DebugState.currentFile,
                    DebugState.currentLine,
                    RuntimeContextType.SCALAR
            );
            
            // Check if eval had an error
            RuntimeScalar evalError = GlobalVariable.getGlobalVariable("main::@");
            if (evalError.getDefinedBoolean() && !evalError.toString().isEmpty()) {
                System.out.println("Error: " + evalError.toString().trim());
            } else {
                // Print the result (like Perl's print)
                System.out.println(result.toString());
            }
        } catch (Exception e) {
            System.out.println("Error evaluating expression: " + e.getMessage());
        } finally {
            // Restore debug mode
            DebugState.debugMode = savedDebugMode;
        }
    }

    /**
     * Handle 'x' (dump) command - evaluate and dump expression structure.
     */
    private static void handleDump(String expr) {
        if (expr.isEmpty()) {
            System.out.println("Usage: x <expression>");
            return;
        }

        // Temporarily disable debug mode during expression evaluation
        boolean savedDebugMode = DebugState.debugMode;
        DebugState.debugMode = false;
        
        try {
            // Wrap expression to use Data::Dumper-style output
            // For now, use a simple approach: evaluate and show type info
            String dumpExpr = "do { use Data::Dumper; local $Data::Dumper::Terse = 1; local $Data::Dumper::Indent = 1; Dumper(" + expr + ") }";
            
            RuntimeScalar result = EvalStringHandler.evalString(
                    dumpExpr,
                    currentCode,
                    currentRegisters,
                    DebugState.currentFile,
                    DebugState.currentLine,
                    RuntimeContextType.SCALAR
            );
            
            // Check if eval had an error
            RuntimeScalar evalError = GlobalVariable.getGlobalVariable("main::@");
            if (evalError.getDefinedBoolean() && !evalError.toString().isEmpty()) {
                // Data::Dumper not available, fall back to simple output
                result = EvalStringHandler.evalString(
                        expr,
                        currentCode,
                        currentRegisters,
                        DebugState.currentFile,
                        DebugState.currentLine,
                        RuntimeContextType.SCALAR
                );
                System.out.println("0  " + result.toString());
            } else {
                System.out.print(result.toString());
            }
        } catch (Exception e) {
            System.out.println("Error evaluating expression: " + e.getMessage());
        } finally {
            // Restore debug mode
            DebugState.debugMode = savedDebugMode;
        }
    }

    /**
     * Called when entering a subroutine (for step-over tracking).
     */
    public static void enterSubroutine() {
        DebugState.callDepth++;
    }

    /**
     * Called when exiting a subroutine (for step-over tracking).
     */
    public static void exitSubroutine() {
        DebugState.callDepth--;
        if (DebugState.callDepth < 0) {
            DebugState.callDepth = 0;
        }
    }
    
    /**
     * Sync debug state from Perl variables ($DB::single, $DB::trace, $DB::signal).
     * Called at each DEBUG opcode to pick up changes made by Perl code.
     */
    private static void syncFromPerlVariables() {
        RuntimeScalar single = GlobalVariable.getGlobalVariable("DB::single");
        RuntimeScalar trace = GlobalVariable.getGlobalVariable("DB::trace");
        RuntimeScalar signal = GlobalVariable.getGlobalVariable("DB::signal");
        
        DebugState.single = single.getBoolean();
        DebugState.trace = trace.getBoolean();
        DebugState.signal = signal.getBoolean();
    }
    
    /**
     * Sync debug state to Perl variables.
     * Called after debugger commands that change stepping state.
     */
    private static void syncToPerlVariables() {
        GlobalVariable.getGlobalVariable("DB::single").set(DebugState.single ? 1 : 0);
        GlobalVariable.getGlobalVariable("DB::trace").set(DebugState.trace ? 1 : 0);
        GlobalVariable.getGlobalVariable("DB::signal").set(DebugState.signal ? 1 : 0);
    }
    
    /**
     * Initialize debug variables at startup.
     * Called when -d flag is used.
     */
    public static void initializeDebugVariables() {
        // Initialize $DB::single to 1 to start in single-step mode
        GlobalVariable.getGlobalVariable("DB::single").set(1);
        GlobalVariable.getGlobalVariable("DB::trace").set(0);
        GlobalVariable.getGlobalVariable("DB::signal").set(0);
        GlobalVariable.getGlobalVariable("DB::filename").set("");
        GlobalVariable.getGlobalVariable("DB::line").set(0);
    }

    /**
     * Sync %DB::sub from DebugState.subLocations.
     * Called periodically to ensure Perl code can access subroutine locations.
     */
    public static void syncDbSub() {
        if (!DebugState.debugMode) {
            return;
        }
        RuntimeHash dbSub = GlobalVariable.getGlobalHash("DB::sub");
        for (var entry : DebugState.subLocations.entrySet()) {
            dbSub.put(entry.getKey(), new RuntimeScalar(entry.getValue()));
        }
    }
}
