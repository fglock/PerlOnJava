package org.perlonjava.runtime.debugger;

import org.perlonjava.backend.bytecode.InterpreterState;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
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

    /**
     * Main debug hook called by DEBUG opcode.
     * Checks if we should stop and handles debugger interaction.
     *
     * @param filename Current source filename
     * @param line     Current line number (1-based)
     */
    public static void debug(String filename, int line) {
        // Sync from Perl $DB::single variable to DebugState
        syncFromPerlVariables();
        
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
        DebugState.single = true;
        syncToPerlVariables();
        return true;
    }

    /**
     * Handle 's' (step) command - step into subroutine calls.
     */
    private static boolean handleStep(String args) {
        // Disable step-over, enable single-step
        DebugState.stepOverDepth = -1;
        DebugState.single = true;
        syncToPerlVariables();
        return true;
    }

    /**
     * Handle 'c' (continue) command - run until breakpoint or end.
     */
    private static boolean handleContinue(String args) {
        // Disable single-step and step-over
        DebugState.single = false;
        DebugState.stepOverDepth = -1;

        // If argument provided, it's a line number for one-time breakpoint
        if (!args.isEmpty()) {
            try {
                int targetLine = Integer.parseInt(args);
                String key = DebugState.currentFile + ":" + targetLine;
                DebugState.breakpoints.add(key);
                // TODO: Mark as one-time breakpoint to remove after hit
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
     * Handle 'h' (help) command.
     */
    private static void handleHelp() {
        System.out.println("Debugger commands:");
        System.out.println("  n          Next (step over) - execute until next statement");
        System.out.println("  s          Step into - step into subroutine calls");
        System.out.println("  c [line]   Continue - run until breakpoint or end");
        System.out.println("  q          Quit - exit the debugger");
        System.out.println("  l [range]  List source (e.g., 'l 10-20' or 'l 15')");
        System.out.println("  .          Show current line");
        System.out.println("  b [line]   Set breakpoint (e.g., 'b 10' or 'b file.pl:10')");
        System.out.println("  B [line]   Delete breakpoint ('B *' deletes all)");
        System.out.println("  L          List all breakpoints");
        System.out.println("  h or ?     Show this help");
        System.out.println("");
        System.out.println("Press Enter to repeat last command (default: n)");
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
}
