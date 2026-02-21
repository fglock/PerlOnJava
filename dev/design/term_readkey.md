# Implementation Plan for Term::ReadKey in Java

## Overview

The goal is to implement a Java version of the Perl `Term::ReadKey` module, which provides terminal control functionalities such as changing terminal modes and reading keys. We will explore three approaches: pure Java, using JLine, and using JNA.

Each approach has its trade-offs. The pure Java approach is simplest but limited. JLine provides a good balance of functionality and ease of use, while JNA offers the most control at the cost of complexity and platform-specific considerations.

## Approach 1: Pure Java

### Pros
- No external dependencies.
- Simple to integrate into existing Java applications.

### Cons
- Limited control over terminal settings.
- May not support all required functionalities, such as non-blocking reads or disabling echo.

### Implementation Steps

1. **Basic Structure**: 
   - Create a `TermReadKey` class extending `PerlModuleBase`.
   - Define methods `ReadMode` and `ReadKey`.

2. **ReadMode Implementation**:
   - Use `System.console()` to interact with the terminal.
   - Implement basic mode handling using Java's standard I/O, but note the limitations (e.g., no native support for disabling echo).

3. **ReadKey Implementation**:
   - Use `System.in` for reading input.
   - Implement non-blocking reads using `InputStream.available()`.
   - Handle blocking reads using `System.console().readPassword()` for password-like input.

## Approach 2: Using JLine

### Pros
- Provides advanced terminal control.
- Cross-platform support.

### Cons
- Adds an external dependency.

### Implementation Steps

1. **Add JLine Dependency**:
   - Include JLine in the project's build configuration (e.g., Maven or Gradle).

2. **Initialize JLine Terminal**:
   - Use JLine's `TerminalBuilder` to create a terminal instance.

3. **Implement ReadMode**:
   - Use JLine's API to change terminal modes, such as disabling echo.

4. **Implement ReadKey**:
   - Use JLine's `LineReader` for reading input with support for non-blocking and timed reads.

## Approach 3: Using JNA

### Pros
- Direct access to native system calls.
- Full control over terminal settings.

### Cons
- Platform-specific code may be required.
- Adds an external dependency.

### Implementation Steps

1. **Add JNA Dependency**:
   - Include JNA in the project's build configuration.

2. **Define Native Methods**:
   - Use JNA to define interfaces for native terminal control functions (e.g., `tcgetattr`, `tcsetattr` on Unix).

3. **Implement ReadMode**:
   - Use native calls to change terminal modes, such as disabling echo and setting raw mode.

4. **Implement ReadKey**:
   - Use native calls for non-blocking reads and handle input directly from the terminal.


# SAMPLE CODE


```
package org.perlonjava.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Term::ReadKey module implementation for PerlonJava.
 * Provides terminal control functionality similar to Perl's Term::ReadKey module.
 */
public class TermReadKey extends PerlModuleBase {

    static RuntimeScalar perlClassName = new RuntimeScalar("Term::ReadKey");

    private static final Map<Integer, String> modeDescriptions = new HashMap<>();

    static {
        modeDescriptions.put(0, "restore");
        modeDescriptions.put(1, "normal");
        modeDescriptions.put(2, "noecho");
        modeDescriptions.put(3, "cbreak");
        modeDescriptions.put(4, "raw");
        modeDescriptions.put(5, "ultra-raw");
    }

    /**
     * Constructor for TermReadKey module.
     */
    public TermReadKey() {
        super("Term::ReadKey", false);
    }

    /**
     * Initializes the Term::ReadKey module by registering methods and exports.
     */
    public static void initialize() {
        TermReadKey termReadKey = new TermReadKey();
        try {
            termReadKey.registerMethod("ReadMode", null);
            termReadKey.registerMethod("ReadKey", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Term::ReadKey method: " + e.getMessage());
        }
    }

    /**
     * Sets the terminal mode.
     *
     * @param args [mode]
     * @param ctx  Context flag
     * @return Success status
     */
    public static RuntimeList ReadMode(RuntimeArray args, int ctx) {
        int mode = args.get(0).getInt();
        String modeDescription = modeDescriptions.getOrDefault(mode, "unknown");

        // Implement terminal mode changes here
        // This is a placeholder implementation
        System.out.println("Setting terminal mode to: " + modeDescription);

        return RuntimeScalarCache.scalarTrue.getList();
    }

    /**
     * Reads a key from the terminal.
     *
     * @param args [mode]
     * @param ctx  Context flag
     * @return The key read from the terminal
     */
    public static RuntimeList ReadKey(RuntimeArray args, int ctx) {
        int mode = args.get(0).getInt();
        Console console = System.console();
        if (console == null) {
            return WarnDie.die(new RuntimeScalar("No console available"), new RuntimeScalar("\n")).getList();
        }

        try {
            if (mode == -1) {
                // Non-blocking read
                InputStream in = System.in;
                if (in.available() > 0) {
                    char key = (char) in.read();
                    return new RuntimeScalar(String.valueOf(key)).getList();
                } else {
                    return new RuntimeScalar().getList(); // Return undef equivalent
                }
            } else {
                // Blocking read
                char[] input = console.readPassword();
                return new RuntimeScalar(new String(input)).getList();
            }
        } catch (IOException e) {
            return WarnDie.die(new RuntimeScalar("Failed to read key"), new RuntimeScalar("\n")).getList();
        }
    }
}
```

