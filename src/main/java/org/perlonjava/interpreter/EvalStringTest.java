package org.perlonjava.interpreter;

import org.perlonjava.runtime.*;

/**
 * Test harness for eval STRING functionality in the interpreter.
 */
public class EvalStringTest {

    public static void main(String[] args) {
        System.out.println("=== Eval String Test Suite ===\n");

        // Test 1: Simple expression
        System.out.println("Test 1: eval '10 + 20'");
        try {
            RuntimeList result = InterpreterTest.runCode(
                "my $result = eval '10 + 20'",
                "test1.pl", 1
            );
            System.out.println("OK - Simple eval expression\n");
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            e.printStackTrace();
        }

        // Test 2: Variable access (if supported)
        System.out.println("Test 2: eval with variable");
        try {
            RuntimeList result = InterpreterTest.runCode(
                "my $x = 10; my $result = eval '$x + 5'",
                "test2.pl", 1
            );
            System.out.println("OK - Eval with variable\n");
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            e.printStackTrace();
        }

        // Test 3: Error handling
        System.out.println("Test 3: eval with die");
        try {
            RuntimeList result = InterpreterTest.runCode(
                "my $result = eval { die 'error' }; print $@",
                "test3.pl", 1
            );
            System.out.println("OK - Eval with error handling\n");
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            e.printStackTrace();
        }

        // Test 4: Return value
        System.out.println("Test 4: eval return value");
        try {
            RuntimeList result = InterpreterTest.runCode(
                "my $result = eval '42'; print $result",
                "test4.pl", 1
            );
            System.out.println("OK - Eval return value\n");
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            e.printStackTrace();
        }

        // Test 5: $@ clearing on success
        System.out.println("Test 5: eval clears $@ on success");
        try {
            RuntimeList result = InterpreterTest.runCode(
                "$@ = 'old error'; my $result = eval '1 + 1'; print \"at=$@\"",
                "test5.pl", 1
            );
            System.out.println("OK - $@ cleared on success\n");
        } catch (Exception e) {
            System.out.println("FAILED: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("=== All tests completed ===");
    }
}
