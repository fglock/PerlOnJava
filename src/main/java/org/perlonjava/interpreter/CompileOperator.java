package org.perlonjava.interpreter;

import org.perlonjava.astnode.*;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.RuntimeContextType;

import java.util.ArrayList;
import java.util.List;

public class CompileOperator {
    public static void visitOperator(BytecodeCompiler bytecodeCompiler, OperatorNode node) {
        // Track token index for error reporting
        bytecodeCompiler.currentTokenIndex = node.getIndex();

        String op = node.operator;

        // Group 1: Variable declarations (my, our, local, state)
        if (op.equals("my") || op.equals("our") || op.equals("local") || op.equals("state")) {
            bytecodeCompiler.compileVariableDeclaration(node, op);
            return;
        }

        // Group 2: Variable reference operators ($, @, %, *, &, \)
        if (op.equals("$") || op.equals("@") || op.equals("%") || op.equals("*") || op.equals("&") || op.equals("\\")) {
            bytecodeCompiler.compileVariableReference(node, op);
            return;
        }

        // Handle remaining operators
        if (op.equals("scalar")) {
            // Force scalar context: scalar(expr)
            // Evaluates the operand and converts the result to scalar
            if (node.operand != null) {
                // Evaluate operand in scalar context
                int savedContext = bytecodeCompiler.currentCallContext;
                bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
                try {
                    node.operand.accept(bytecodeCompiler);
                    int operandReg = bytecodeCompiler.lastResultReg;

                    // Emit ARRAY_SIZE to convert to scalar
                    // This handles arrays/hashes (converts to size) and passes through scalars
                    int rd = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.ARRAY_SIZE);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(operandReg);

                    bytecodeCompiler.lastResultReg = rd;
                } finally {
                    bytecodeCompiler.currentCallContext = savedContext;
                }
            } else {
                bytecodeCompiler.throwCompilerException("scalar operator requires an operand");
            }
            return;
        } else if (op.equals("package") || op.equals("class")) {
            // Package/Class declaration: package Foo; or class Foo;
            // This updates the current package context for subsequent variable declarations
            if (node.operand instanceof IdentifierNode) {
                String packageName = ((IdentifierNode) node.operand).name;

                // Check if this is a class declaration (either "class" operator or isClass annotation)
                Boolean isClassAnnotation = (Boolean) node.getAnnotation("isClass");
                boolean isClass = op.equals("class") || (isClassAnnotation != null && isClassAnnotation);

                // Update the current package/class in symbol table
                // This tracks package name, isClass flag, and version
                bytecodeCompiler.symbolTable.setCurrentPackage(packageName, isClass);

                // Register as Perl 5.38+ class for proper stringification if needed
                if (isClass) {
                    org.perlonjava.runtime.ClassRegistry.registerClass(packageName);
                }

                bytecodeCompiler.lastResultReg = -1;  // No runtime value
            } else {
                bytecodeCompiler.throwCompilerException(op + " operator requires an identifier");
            }
        } else if (op.equals("say") || op.equals("print")) {
            // say/print $x
            if (node.operand != null) {
                node.operand.accept(bytecodeCompiler);
                int rs = bytecodeCompiler.lastResultReg;

                bytecodeCompiler.emit(op.equals("say") ? Opcodes.SAY : Opcodes.PRINT);
                bytecodeCompiler.emitReg(rs);
            }
        } else if (op.equals("not") || op.equals("!")) {
            // Logical NOT operator: not $x or !$x
            // Evaluate operand in scalar context (need boolean value)
            if (node.operand != null) {
                int savedContext = bytecodeCompiler.currentCallContext;
                bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
                node.operand.accept(bytecodeCompiler);
                int rs = bytecodeCompiler.lastResultReg;
                bytecodeCompiler.currentCallContext = savedContext;

                // Allocate result register
                int rd = bytecodeCompiler.allocateRegister();

                // Emit NOT opcode
                bytecodeCompiler.emit(Opcodes.NOT);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs);

                bytecodeCompiler.lastResultReg = rd;
            } else {
                bytecodeCompiler.throwCompilerException("NOT operator requires operand");
            }
        } else if (op.equals("~") || op.equals("binary~")) {
            // Bitwise NOT operator: ~$x or binary~$x
            // Evaluate operand and emit BITWISE_NOT_BINARY opcode
            if (node.operand != null) {
                node.operand.accept(bytecodeCompiler);
                int rs = bytecodeCompiler.lastResultReg;

                // Allocate result register
                int rd = bytecodeCompiler.allocateRegister();

                // Emit BITWISE_NOT_BINARY opcode
                bytecodeCompiler.emit(Opcodes.BITWISE_NOT_BINARY);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs);

                bytecodeCompiler.lastResultReg = rd;
            } else {
                bytecodeCompiler.throwCompilerException("Bitwise NOT operator requires operand");
            }
        } else if (op.equals("~.")) {
            // String bitwise NOT operator: ~.$x
            // Evaluate operand and emit BITWISE_NOT_STRING opcode
            if (node.operand != null) {
                node.operand.accept(bytecodeCompiler);
                int rs = bytecodeCompiler.lastResultReg;

                // Allocate result register
                int rd = bytecodeCompiler.allocateRegister();

                // Emit BITWISE_NOT_STRING opcode
                bytecodeCompiler.emit(Opcodes.BITWISE_NOT_STRING);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs);

                bytecodeCompiler.lastResultReg = rd;
            } else {
                bytecodeCompiler.throwCompilerException("String bitwise NOT operator requires operand");
            }
        } else if (op.equals("defined")) {
            // Defined operator: defined($x)
            // Check if value is defined (not undef)
            if (node.operand != null) {
                node.operand.accept(bytecodeCompiler);
                int rs = bytecodeCompiler.lastResultReg;

                // Allocate result register
                int rd = bytecodeCompiler.allocateRegister();

                // Emit DEFINED opcode
                bytecodeCompiler.emit(Opcodes.DEFINED);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs);

                bytecodeCompiler.lastResultReg = rd;
            } else {
                bytecodeCompiler.throwCompilerException("defined operator requires operand");
            }
        } else if (op.equals("ref")) {
            // Ref operator: ref($x)
            // Get reference type (blessed class name or base type)
            if (node.operand == null) {
                bytecodeCompiler.throwCompilerException("ref requires an argument");
            }

            // Compile the operand
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (list.elements.isEmpty()) {
                    bytecodeCompiler.throwCompilerException("ref requires an argument");
                }
                // Get first element
                list.elements.get(0).accept(bytecodeCompiler);
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;

            // Allocate result register
            int rd = bytecodeCompiler.allocateRegister();

            // Emit REF opcode
            bytecodeCompiler.emit(Opcodes.REF);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("prototype")) {
            // Prototype operator: prototype(\&func) or prototype("func_name")
            // Returns the prototype string for a subroutine
            if (node.operand == null) {
                bytecodeCompiler.throwCompilerException("prototype requires an argument");
            }

            // Compile the operand (code reference or function name)
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (list.elements.isEmpty()) {
                    bytecodeCompiler.throwCompilerException("prototype requires an argument");
                }
                // Get first element
                list.elements.get(0).accept(bytecodeCompiler);
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;

            // Allocate result register
            int rd = bytecodeCompiler.allocateRegister();

            // Add current package to string pool
            int packageIdx = bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage());

            // Emit PROTOTYPE opcode
            bytecodeCompiler.emit(Opcodes.PROTOTYPE);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.emitInt(packageIdx);

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("quoteRegex")) {
            // Quote regex operator: qr{pattern}flags
            // operand is a ListNode with [pattern, flags]
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                bytecodeCompiler.throwCompilerException("quoteRegex requires pattern and flags");
            }

            ListNode operand = (ListNode) node.operand;
            if (operand.elements.size() < 2) {
                bytecodeCompiler.throwCompilerException("quoteRegex requires pattern and flags");
            }

            // Compile pattern and flags
            operand.elements.get(0).accept(bytecodeCompiler);  // Pattern
            int patternReg = bytecodeCompiler.lastResultReg;

            operand.elements.get(1).accept(bytecodeCompiler);  // Flags
            int flagsReg = bytecodeCompiler.lastResultReg;

            // Allocate result register
            int rd = bytecodeCompiler.allocateRegister();

            // Emit QUOTE_REGEX opcode
            bytecodeCompiler.emit(Opcodes.QUOTE_REGEX);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(patternReg);
            bytecodeCompiler.emitReg(flagsReg);

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("++") || op.equals("--") || op.equals("++postfix") || op.equals("--postfix")) {
            // Pre/post increment/decrement
            boolean isPostfix = op.endsWith("postfix");
            boolean isIncrement = op.startsWith("++");

            if (node.operand instanceof IdentifierNode) {
                String varName = ((IdentifierNode) node.operand).name;

                if (bytecodeCompiler.hasVariable(varName)) {
                    int varReg = bytecodeCompiler.getVariableRegister(varName);

                    // Use optimized autoincrement/decrement opcodes
                    if (isPostfix) {
                        // Postfix: returns old value before modifying
                        // Need TWO registers: one for result (old value), one for variable
                        int resultReg = bytecodeCompiler.allocateRegister();
                        if (isIncrement) {
                            bytecodeCompiler.emit(Opcodes.POST_AUTOINCREMENT);
                        } else {
                            bytecodeCompiler.emit(Opcodes.POST_AUTODECREMENT);
                        }
                        bytecodeCompiler.emitReg(resultReg);  // Destination for old value
                        bytecodeCompiler.emitReg(varReg);     // Variable to modify in-place
                        bytecodeCompiler.lastResultReg = resultReg;
                    } else {
                        // Prefix: returns new value after modifying
                        if (isIncrement) {
                            bytecodeCompiler.emit(Opcodes.PRE_AUTOINCREMENT);
                        } else {
                            bytecodeCompiler.emit(Opcodes.PRE_AUTODECREMENT);
                        }
                        bytecodeCompiler.emitReg(varReg);
                        bytecodeCompiler.lastResultReg = varReg;
                    }
                } else {
                    bytecodeCompiler.throwCompilerException("Increment/decrement of non-lexical variable not yet supported");
                }
            } else if (node.operand instanceof OperatorNode) {
                // Handle $x++
                OperatorNode innerOp = (OperatorNode) node.operand;
                if (innerOp.operator.equals("$") && innerOp.operand instanceof IdentifierNode) {
                    String varName = "$" + ((IdentifierNode) innerOp.operand).name;

                    if (bytecodeCompiler.hasVariable(varName)) {
                        int varReg = bytecodeCompiler.getVariableRegister(varName);

                        // Use optimized autoincrement/decrement opcodes
                        if (isPostfix) {
                            // Postfix: returns old value before modifying
                            // Need TWO registers: one for result (old value), one for variable
                            int resultReg = bytecodeCompiler.allocateRegister();
                            if (isIncrement) {
                                bytecodeCompiler.emit(Opcodes.POST_AUTOINCREMENT);
                            } else {
                                bytecodeCompiler.emit(Opcodes.POST_AUTODECREMENT);
                            }
                            bytecodeCompiler.emitReg(resultReg);  // Destination for old value
                            bytecodeCompiler.emitReg(varReg);     // Variable to modify in-place
                            bytecodeCompiler.lastResultReg = resultReg;
                        } else {
                            if (isIncrement) {
                                bytecodeCompiler.emit(Opcodes.PRE_AUTOINCREMENT);
                            } else {
                                bytecodeCompiler.emit(Opcodes.PRE_AUTODECREMENT);
                            }
                            bytecodeCompiler.emitReg(varReg);
                            bytecodeCompiler.lastResultReg = varReg;
                        }
                    } else {
                        // Global variable increment/decrement
                        // Normalize global variable name (remove sigil, add package)
                        String bareVarName = varName.substring(1);  // Remove "$"
                        String normalizedName = NameNormalizer.normalizeVariableName(bareVarName, bytecodeCompiler.getCurrentPackage());
                        int nameIdx = bytecodeCompiler.addToStringPool(normalizedName);

                        // Load global variable
                        int globalReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                        bytecodeCompiler.emitReg(globalReg);
                        bytecodeCompiler.emit(nameIdx);

                        // Apply increment/decrement
                        if (isPostfix) {
                            // Postfix: returns old value before modifying
                            // Need TWO registers: one for result (old value), one for variable
                            int resultReg = bytecodeCompiler.allocateRegister();
                            if (isIncrement) {
                                bytecodeCompiler.emit(Opcodes.POST_AUTOINCREMENT);
                            } else {
                                bytecodeCompiler.emit(Opcodes.POST_AUTODECREMENT);
                            }
                            bytecodeCompiler.emitReg(resultReg);   // Destination for old value
                            bytecodeCompiler.emitReg(globalReg);   // Variable to modify in-place
                            bytecodeCompiler.lastResultReg = resultReg;
                        } else {
                            if (isIncrement) {
                                bytecodeCompiler.emit(Opcodes.PRE_AUTOINCREMENT);
                            } else {
                                bytecodeCompiler.emit(Opcodes.PRE_AUTODECREMENT);
                            }
                            bytecodeCompiler.emitReg(globalReg);
                            bytecodeCompiler.lastResultReg = globalReg;
                        }

                        // NOTE: Do NOT store back to global variable!
                        // The POST/PRE_AUTO* opcodes modify the global variable directly
                        // and return the appropriate value (old for postfix, new for prefix).
                        // Storing back would overwrite the modification with the return value.
                    }
                } else {
                    bytecodeCompiler.throwCompilerException("Invalid operand for increment/decrement operator");
                }
            } else {
                bytecodeCompiler.throwCompilerException("Increment/decrement operator requires operand");
            }
        } else if (op.equals("return")) {
            // return $expr;
            // Also handles 'goto &NAME' tail calls (parsed as 'return (coderef(@_))')

            // Check if this is a 'goto &NAME' or 'goto EXPR' tail call
            // Pattern: return with ListNode containing single BinaryOperatorNode("(")
            // where left is OperatorNode("&") and right is @_
            if (node.operand instanceof ListNode list && list.elements.size() == 1) {
                Node firstElement = list.elements.getFirst();
                if (firstElement instanceof BinaryOperatorNode callNode && callNode.operator.equals("(")) {
                    Node callTarget = callNode.left;

                    // Handle &sub syntax (goto &foo)
                    if (callTarget instanceof OperatorNode opNode && opNode.operator.equals("&")) {
                        // This is a tail call: goto &sub
                        // Evaluate the code reference in scalar context
                        int savedContext = bytecodeCompiler.currentCallContext;
                        bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
                        callTarget.accept(bytecodeCompiler);
                        int codeRefReg = bytecodeCompiler.lastResultReg;

                        // Evaluate the arguments in list context (usually @_)
                        bytecodeCompiler.currentCallContext = RuntimeContextType.LIST;
                        callNode.right.accept(bytecodeCompiler);
                        int argsReg = bytecodeCompiler.lastResultReg;
                        bytecodeCompiler.currentCallContext = savedContext;

                        // Allocate register for call result
                        int rd = bytecodeCompiler.allocateRegister();

                        // Emit CALL_SUB to invoke the code reference with proper context
                        bytecodeCompiler.emit(Opcodes.CALL_SUB);
                        bytecodeCompiler.emitReg(rd);  // Result register
                        bytecodeCompiler.emitReg(codeRefReg); // Code reference register
                        bytecodeCompiler.emitReg(argsReg); // Arguments register
                        bytecodeCompiler.emit(savedContext); // Use saved calling context for the tail call

                        // Then return the result
                        bytecodeCompiler.emitWithToken(Opcodes.RETURN, node.getIndex());
                        bytecodeCompiler.emitReg(rd);

                        bytecodeCompiler.lastResultReg = -1;
                        return;
                    }
                }
            }

            if (node.operand != null) {
                // Regular return with expression
                node.operand.accept(bytecodeCompiler);
                int exprReg = bytecodeCompiler.lastResultReg;

                // Emit RETURN with expression register
                bytecodeCompiler.emitWithToken(Opcodes.RETURN, node.getIndex());
                bytecodeCompiler.emitReg(exprReg);
            } else {
                // return; (no value - return empty list/undef)
                int undefReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                bytecodeCompiler.emitReg(undefReg);

                bytecodeCompiler.emitWithToken(Opcodes.RETURN, node.getIndex());
                bytecodeCompiler.emitReg(undefReg);
            }
            bytecodeCompiler.lastResultReg = -1; // No result after return
        } else if (op.equals("last") || op.equals("next") || op.equals("redo")) {
            // Loop control operators: last/next/redo [LABEL]
            bytecodeCompiler.handleLoopControlOperator(node, op);
            bytecodeCompiler.lastResultReg = -1; // No result after control flow
        } else if (op.equals("rand")) {
            // rand() or rand($max)
            // Calls Random.rand(max) where max defaults to 1
            int rd = bytecodeCompiler.allocateRegister();

            if (node.operand != null) {
                // rand($max) - evaluate operand
                node.operand.accept(bytecodeCompiler);
                int maxReg = bytecodeCompiler.lastResultReg;

                // Emit RAND opcode
                bytecodeCompiler.emit(Opcodes.RAND);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(maxReg);
            } else {
                // rand() with no argument - defaults to 1
                int oneReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.LOAD_INT);
                bytecodeCompiler.emitReg(oneReg);
                bytecodeCompiler.emitInt(1);

                // Emit RAND opcode
                bytecodeCompiler.emit(Opcodes.RAND);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(oneReg);
            }

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("sleep")) {
            // sleep $seconds
            // Calls Time.sleep(seconds)
            int rd = bytecodeCompiler.allocateRegister();

            if (node.operand != null) {
                // sleep($seconds) - evaluate operand
                node.operand.accept(bytecodeCompiler);
                int secondsReg = bytecodeCompiler.lastResultReg;

                // Emit direct opcode SLEEP_OP
                bytecodeCompiler.emit(Opcodes.SLEEP_OP);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(secondsReg);
            } else {
                // sleep with no argument - defaults to infinity (but we'll use a large number)
                int maxReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.LOAD_INT);
                bytecodeCompiler.emitReg(maxReg);
                bytecodeCompiler.emitInt(Integer.MAX_VALUE);

                bytecodeCompiler.emit(Opcodes.SLEEP_OP);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(maxReg);
            }

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("study")) {
            // study $var
            // In modern Perl, study is a no-op that always returns true
            // We evaluate the operand for side effects, then return 1

            if (node.operand != null) {
                // Evaluate operand for side effects (though typically there are none)
                node.operand.accept(bytecodeCompiler);
            }

            // Return 1 (true)
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.LOAD_INT);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitInt(1);

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("require")) {
            // require MODULE_NAME or require VERSION
            // Evaluate operand in scalar context
            int savedContext = bytecodeCompiler.currentCallContext;
            bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
            try {
                node.operand.accept(bytecodeCompiler);
                int operandReg = bytecodeCompiler.lastResultReg;

                // Call ModuleOperators.require()
                int rd = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.REQUIRE);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(operandReg);

                bytecodeCompiler.lastResultReg = rd;
            } finally {
                bytecodeCompiler.currentCallContext = savedContext;
            }
        } else if (op.equals("pos")) {
            // pos($var) - get or set regex match position
            // Returns an lvalue that can be assigned to
            int savedContext = bytecodeCompiler.currentCallContext;
            bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
            try {
                node.operand.accept(bytecodeCompiler);
                int operandReg = bytecodeCompiler.lastResultReg;

                // Call RuntimeScalar.pos()
                int rd = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.POS);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(operandReg);

                bytecodeCompiler.lastResultReg = rd;
            } finally {
                bytecodeCompiler.currentCallContext = savedContext;
            }
        } else if (op.equals("index") || op.equals("rindex")) {
            // index(str, substr, pos?) or rindex(str, substr, pos?)
            if (node.operand instanceof ListNode) {
                ListNode args = (ListNode) node.operand;

                int savedContext = bytecodeCompiler.currentCallContext;
                bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
                try {
                    // Evaluate first arg (string)
                    if (args.elements.isEmpty()) {
                        bytecodeCompiler.throwCompilerException("Not enough arguments for " + op);
                    }
                    args.elements.get(0).accept(bytecodeCompiler);
                    int strReg = bytecodeCompiler.lastResultReg;

                    // Evaluate second arg (substring)
                    if (args.elements.size() < 2) {
                        bytecodeCompiler.throwCompilerException("Not enough arguments for " + op);
                    }
                    args.elements.get(1).accept(bytecodeCompiler);
                    int substrReg = bytecodeCompiler.lastResultReg;

                    // Evaluate third arg (position) - optional, defaults to undef
                    int posReg;
                    if (args.elements.size() >= 3) {
                        args.elements.get(2).accept(bytecodeCompiler);
                        posReg = bytecodeCompiler.lastResultReg;
                    } else {
                        posReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                        bytecodeCompiler.emitReg(posReg);
                    }

                    // Call index or rindex
                    int rd = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(op.equals("index") ? Opcodes.INDEX : Opcodes.RINDEX);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(strReg);
                    bytecodeCompiler.emitReg(substrReg);
                    bytecodeCompiler.emitReg(posReg);

                    bytecodeCompiler.lastResultReg = rd;
                } finally {
                    bytecodeCompiler.currentCallContext = savedContext;
                }
            } else {
                bytecodeCompiler.throwCompilerException(op + " requires a list of arguments");
            }
        } else if (op.equals("stat") || op.equals("lstat")) {
            // stat FILE or lstat FILE
            int savedContext = bytecodeCompiler.currentCallContext;
            bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
            try {
                node.operand.accept(bytecodeCompiler);
                int operandReg = bytecodeCompiler.lastResultReg;

                int rd = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(op.equals("stat") ? Opcodes.STAT : Opcodes.LSTAT);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(operandReg);
                bytecodeCompiler.emit(savedContext);  // Pass calling context

                bytecodeCompiler.lastResultReg = rd;
            } finally {
                bytecodeCompiler.currentCallContext = savedContext;
            }
        } else if (op.startsWith("-") && op.length() == 2) {
            // File test operators: -r, -w, -x, etc.

            // Check if operand is the special filehandle "_"
            boolean isUnderscoreOperand = (node.operand instanceof IdentifierNode)
                    && ((IdentifierNode) node.operand).name.equals("_");

            if (isUnderscoreOperand) {
                // Special case: -r _ uses cached file handle
                // Call FileTestOperator.fileTestLastHandle(String)
                int rd = bytecodeCompiler.allocateRegister();
                int operatorStrIndex = bytecodeCompiler.addToStringPool(op);

                // Emit FILETEST_LASTHANDLE opcode
                bytecodeCompiler.emit(Opcodes.FILETEST_LASTHANDLE);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emit(operatorStrIndex);

                bytecodeCompiler.lastResultReg = rd;
            } else {
                // Normal case: evaluate operand and test it
                int savedContext = bytecodeCompiler.currentCallContext;
                bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
                try {
                    node.operand.accept(bytecodeCompiler);
                    int operandReg = bytecodeCompiler.lastResultReg;

                    int rd = bytecodeCompiler.allocateRegister();

                    // Map operator to opcode
                    char testChar = op.charAt(1);
                    short opcode;
                    switch (testChar) {
                        case 'r':
                            opcode = Opcodes.FILETEST_R;
                            break;
                        case 'w':
                            opcode = Opcodes.FILETEST_W;
                            break;
                        case 'x':
                            opcode = Opcodes.FILETEST_X;
                            break;
                        case 'o':
                            opcode = Opcodes.FILETEST_O;
                            break;
                        case 'R':
                            opcode = Opcodes.FILETEST_R_REAL;
                            break;
                        case 'W':
                            opcode = Opcodes.FILETEST_W_REAL;
                            break;
                        case 'X':
                            opcode = Opcodes.FILETEST_X_REAL;
                            break;
                        case 'O':
                            opcode = Opcodes.FILETEST_O_REAL;
                            break;
                        case 'e':
                            opcode = Opcodes.FILETEST_E;
                            break;
                        case 'z':
                            opcode = Opcodes.FILETEST_Z;
                            break;
                        case 's':
                            opcode = Opcodes.FILETEST_S;
                            break;
                        case 'f':
                            opcode = Opcodes.FILETEST_F;
                            break;
                        case 'd':
                            opcode = Opcodes.FILETEST_D;
                            break;
                        case 'l':
                            opcode = Opcodes.FILETEST_L;
                            break;
                        case 'p':
                            opcode = Opcodes.FILETEST_P;
                            break;
                        case 'S':
                            opcode = Opcodes.FILETEST_S_UPPER;
                            break;
                        case 'b':
                            opcode = Opcodes.FILETEST_B;
                            break;
                        case 'c':
                            opcode = Opcodes.FILETEST_C;
                            break;
                        case 't':
                            opcode = Opcodes.FILETEST_T;
                            break;
                        case 'u':
                            opcode = Opcodes.FILETEST_U;
                            break;
                        case 'g':
                            opcode = Opcodes.FILETEST_G;
                            break;
                        case 'k':
                            opcode = Opcodes.FILETEST_K;
                            break;
                        case 'T':
                            opcode = Opcodes.FILETEST_T_UPPER;
                            break;
                        case 'B':
                            opcode = Opcodes.FILETEST_B_UPPER;
                            break;
                        case 'M':
                            opcode = Opcodes.FILETEST_M;
                            break;
                        case 'A':
                            opcode = Opcodes.FILETEST_A;
                            break;
                        case 'C':
                            opcode = Opcodes.FILETEST_C_UPPER;
                            break;
                        default:
                            bytecodeCompiler.throwCompilerException("Unsupported file test operator: " + op);
                            return;
                    }

                    bytecodeCompiler.emit(opcode);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(operandReg);

                    bytecodeCompiler.lastResultReg = rd;
                } finally {
                    bytecodeCompiler.currentCallContext = savedContext;
                }
            }
        } else if (op.equals("die")) {
            // die $message;
            if (node.operand != null) {
                // Evaluate die message
                node.operand.accept(bytecodeCompiler);
                int msgReg = bytecodeCompiler.lastResultReg;

                // Precompute location message at compile time (zero overhead!)
                String locationMsg;
                // Use annotation from AST node which has the correct line number
                Object lineObj = node.getAnnotation("line");
                Object fileObj = node.getAnnotation("file");
                if (lineObj != null && fileObj != null) {
                    String fileName = fileObj.toString();
                    int lineNumber = Integer.parseInt(lineObj.toString());
                    locationMsg = " at " + fileName + " line " + lineNumber;
                } else if (bytecodeCompiler.errorUtil != null) {
                    // Fallback to errorUtil if annotations not available
                    String fileName = bytecodeCompiler.errorUtil.getFileName();
                    int lineNumber = bytecodeCompiler.errorUtil.getLineNumberAccurate(node.getIndex());
                    locationMsg = " at " + fileName + " line " + lineNumber;
                } else {
                    // Final fallback if neither available
                    locationMsg = " at " + bytecodeCompiler.sourceName + " line " + bytecodeCompiler.sourceLine;
                }

                int locationReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.LOAD_STRING);
                bytecodeCompiler.emitReg(locationReg);
                bytecodeCompiler.emit(bytecodeCompiler.addToStringPool(locationMsg));

                // Emit DIE with both message and precomputed location
                bytecodeCompiler.emitWithToken(Opcodes.DIE, node.getIndex());
                bytecodeCompiler.emitReg(msgReg);
                bytecodeCompiler.emitReg(locationReg);
            } else {
                // die; (no message - use $@)
                // For now, emit with undef register
                int undefReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                bytecodeCompiler.emitReg(undefReg);

                // Precompute location message for bare die
                String locationMsg;
                if (bytecodeCompiler.errorUtil != null) {
                    String fileName = bytecodeCompiler.errorUtil.getFileName();
                    int lineNumber = bytecodeCompiler.errorUtil.getLineNumber(node.getIndex());
                    locationMsg = " at " + fileName + " line " + lineNumber;
                } else {
                    locationMsg = " at " + bytecodeCompiler.sourceName + " line " + bytecodeCompiler.sourceLine;
                }

                int locationReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.LOAD_STRING);
                bytecodeCompiler.emitReg(locationReg);
                bytecodeCompiler.emitInt(bytecodeCompiler.addToStringPool(locationMsg));

                bytecodeCompiler.emitWithToken(Opcodes.DIE, node.getIndex());
                bytecodeCompiler.emitReg(undefReg);
                bytecodeCompiler.emitReg(locationReg);
            }
            bytecodeCompiler.lastResultReg = -1; // No result after die
        } else if (op.equals("warn")) {
            // warn $message;
            if (node.operand != null) {
                // Evaluate warn message
                node.operand.accept(bytecodeCompiler);
                int msgReg = bytecodeCompiler.lastResultReg;

                // Precompute location message at compile time
                String locationMsg;
                // Use annotation from AST node which has the correct line number
                Object lineObj = node.getAnnotation("line");
                Object fileObj = node.getAnnotation("file");
                if (lineObj != null && fileObj != null) {
                    String fileName = fileObj.toString();
                    int lineNumber = Integer.parseInt(lineObj.toString());
                    locationMsg = " at " + fileName + " line " + lineNumber;
                } else if (bytecodeCompiler.errorUtil != null) {
                    // Fallback to errorUtil if annotations not available
                    String fileName = bytecodeCompiler.errorUtil.getFileName();
                    int lineNumber = bytecodeCompiler.errorUtil.getLineNumberAccurate(node.getIndex());
                    locationMsg = " at " + fileName + " line " + lineNumber;
                } else {
                    // Final fallback if neither available
                    locationMsg = " at " + bytecodeCompiler.sourceName + " line " + bytecodeCompiler.sourceLine;
                }

                int locationReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.LOAD_STRING);
                bytecodeCompiler.emitReg(locationReg);
                bytecodeCompiler.emit(bytecodeCompiler.addToStringPool(locationMsg));

                // Emit WARN with both message and precomputed location
                bytecodeCompiler.emitWithToken(Opcodes.WARN, node.getIndex());
                bytecodeCompiler.emitReg(msgReg);
                bytecodeCompiler.emitReg(locationReg);
            } else {
                // warn; (no message - use $@)
                int undefReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                bytecodeCompiler.emitReg(undefReg);

                // Precompute location message for bare warn
                String locationMsg;
                if (bytecodeCompiler.errorUtil != null) {
                    String fileName = bytecodeCompiler.errorUtil.getFileName();
                    int lineNumber = bytecodeCompiler.errorUtil.getLineNumber(node.getIndex());
                    locationMsg = " at " + fileName + " line " + lineNumber;
                } else {
                    locationMsg = " at " + bytecodeCompiler.sourceName + " line " + bytecodeCompiler.sourceLine;
                }

                int locationReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.LOAD_STRING);
                bytecodeCompiler.emitReg(locationReg);
                bytecodeCompiler.emitInt(bytecodeCompiler.addToStringPool(locationMsg));

                bytecodeCompiler.emitWithToken(Opcodes.WARN, node.getIndex());
                bytecodeCompiler.emitReg(undefReg);
                bytecodeCompiler.emitReg(locationReg);
            }
            // warn returns 1 (true) in Perl
            int resultReg = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.LOAD_INT);
            bytecodeCompiler.emitReg(resultReg);
            bytecodeCompiler.emitInt(1);
            bytecodeCompiler.lastResultReg = resultReg;
        } else if (op.equals("eval")) {
            // eval $string;
            if (node.operand != null) {
                // Evaluate eval operand (the code string)
                node.operand.accept(bytecodeCompiler);
                int stringReg = bytecodeCompiler.lastResultReg;

                // Allocate register for result
                int rd = bytecodeCompiler.allocateRegister();

                // Emit direct opcode EVAL_STRING
                bytecodeCompiler.emitWithToken(Opcodes.EVAL_STRING, node.getIndex());
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(stringReg);

                bytecodeCompiler.lastResultReg = rd;
            } else {
                // eval; (no operand - return undef)
                int undefReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
                bytecodeCompiler.emitReg(undefReg);
                bytecodeCompiler.lastResultReg = undefReg;
            }
        } else if (op.equals("select")) {
            // select FILEHANDLE or select()
            // SELECT is a fast opcode (used in every print statement)
            // Format: [SELECT] [rd] [rs_list]
            // Effect: rd = IOOperator.select(registers[rs_list], SCALAR)

            int rd = bytecodeCompiler.allocateRegister();

            if (node.operand != null && node.operand instanceof ListNode) {
                // select FILEHANDLE or select() with arguments
                // Compile the operand (ListNode containing filehandle ref)
                node.operand.accept(bytecodeCompiler);
                int listReg = bytecodeCompiler.lastResultReg;

                // Emit SELECT opcode
                bytecodeCompiler.emitWithToken(Opcodes.SELECT, node.getIndex());
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(listReg);
            } else {
                // select() with no arguments - returns current filehandle
                // Create empty list
                bytecodeCompiler.emit(Opcodes.CREATE_LIST);
                int listReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emitReg(listReg);
                bytecodeCompiler.emit(0); // count = 0

                // Emit SELECT opcode
                bytecodeCompiler.emitWithToken(Opcodes.SELECT, node.getIndex());
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(listReg);
            }

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("undef")) {
            // undef operator - returns undefined value
            // Can be used standalone: undef
            // Or with an operand to undef a variable: undef $x (not implemented yet)
            int undefReg = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.LOAD_UNDEF);
            bytecodeCompiler.emitReg(undefReg);
            bytecodeCompiler.lastResultReg = undefReg;
        } else if (op.equals("unaryMinus")) {
            // Unary minus: -$x
            // Compile operand
            node.operand.accept(bytecodeCompiler);
            int operandReg = bytecodeCompiler.lastResultReg;

            // Allocate result register
            int rd = bytecodeCompiler.allocateRegister();

            // Emit NEG_SCALAR
            bytecodeCompiler.emit(Opcodes.NEG_SCALAR);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(operandReg);

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("pop")) {
            // Array pop: $x = pop @array or $x = pop @$ref
            // operand: ListNode containing OperatorNode("@", IdentifierNode or OperatorNode)
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                bytecodeCompiler.throwCompilerException("pop requires array argument");
            }

            ListNode list = (ListNode) node.operand;
            if (list.elements.isEmpty() || !(list.elements.get(0) instanceof OperatorNode)) {
                bytecodeCompiler.throwCompilerException("pop requires array variable");
            }

            OperatorNode arrayOp = (OperatorNode) list.elements.get(0);
            if (!arrayOp.operator.equals("@")) {
                bytecodeCompiler.throwCompilerException("pop requires array variable: pop @array");
            }

            int arrayReg = -1;  // Will be assigned in if/else blocks

            if (arrayOp.operand instanceof IdentifierNode) {
                // pop @array
                String varName = "@" + ((IdentifierNode) arrayOp.operand).name;

                // Get the array - check lexical first, then global
                if (bytecodeCompiler.hasVariable(varName)) {
                    // Lexical array
                    arrayReg = bytecodeCompiler.getVariableRegister(varName);
                } else {
                    // Global array - load it
                    arrayReg = bytecodeCompiler.allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) arrayOp.operand).name, bytecodeCompiler.getCurrentPackage());
                    int nameIdx = bytecodeCompiler.addToStringPool(globalArrayName);
                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    bytecodeCompiler.emitReg(arrayReg);
                    bytecodeCompiler.emit(nameIdx);
                }
            } else if (arrayOp.operand instanceof OperatorNode) {
                // pop @$ref - dereference first
                arrayOp.operand.accept(bytecodeCompiler);
                int refReg = bytecodeCompiler.lastResultReg;

                // Dereference to get the array
                arrayReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                bytecodeCompiler.emitReg(arrayReg);
                bytecodeCompiler.emitReg(refReg);
            } else {
                bytecodeCompiler.throwCompilerException("pop requires array variable or dereferenced array: pop @array or pop @$ref");
            }

            // Allocate result register
            int rd = bytecodeCompiler.allocateRegister();

            // Emit ARRAY_POP
            bytecodeCompiler.emit(Opcodes.ARRAY_POP);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(arrayReg);

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("shift")) {
            // Array shift: $x = shift @array or $x = shift @$ref
            // operand: ListNode containing OperatorNode("@", IdentifierNode or OperatorNode)
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                bytecodeCompiler.throwCompilerException("shift requires array argument");
            }

            ListNode list = (ListNode) node.operand;
            if (list.elements.isEmpty() || !(list.elements.get(0) instanceof OperatorNode)) {
                bytecodeCompiler.throwCompilerException("shift requires array variable");
            }

            OperatorNode arrayOp = (OperatorNode) list.elements.get(0);
            if (!arrayOp.operator.equals("@")) {
                bytecodeCompiler.throwCompilerException("shift requires array variable: shift @array");
            }

            int arrayReg = -1;  // Will be assigned in if/else blocks

            if (arrayOp.operand instanceof IdentifierNode) {
                // shift @array
                String varName = "@" + ((IdentifierNode) arrayOp.operand).name;

                // Get the array - check lexical first, then global
                if (bytecodeCompiler.hasVariable(varName)) {
                    // Lexical array
                    arrayReg = bytecodeCompiler.getVariableRegister(varName);
                } else {
                    // Global array - load it
                    arrayReg = bytecodeCompiler.allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) arrayOp.operand).name, bytecodeCompiler.getCurrentPackage());
                    int nameIdx = bytecodeCompiler.addToStringPool(globalArrayName);
                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    bytecodeCompiler.emitReg(arrayReg);
                    bytecodeCompiler.emit(nameIdx);
                }
            } else if (arrayOp.operand instanceof OperatorNode) {
                // shift @$ref - dereference first
                arrayOp.operand.accept(bytecodeCompiler);
                int refReg = bytecodeCompiler.lastResultReg;

                // Dereference to get the array
                arrayReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                bytecodeCompiler.emitReg(arrayReg);
                bytecodeCompiler.emitReg(refReg);
            } else {
                bytecodeCompiler.throwCompilerException("shift requires array variable or dereferenced array: shift @array or shift @$ref");
            }

            // Allocate result register
            int rd = bytecodeCompiler.allocateRegister();

            // Emit ARRAY_SHIFT
            bytecodeCompiler.emit(Opcodes.ARRAY_SHIFT);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(arrayReg);

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("splice")) {
            // Array splice: splice @array, offset, length, @list
            // operand: ListNode containing [@array, offset, length, replacement_list]
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                bytecodeCompiler.throwCompilerException("splice requires array and arguments");
            }

            ListNode list = (ListNode) node.operand;
            if (list.elements.isEmpty() || !(list.elements.get(0) instanceof OperatorNode)) {
                bytecodeCompiler.throwCompilerException("splice requires array variable");
            }

            // First element is the array
            OperatorNode arrayOp = (OperatorNode) list.elements.get(0);
            if (!arrayOp.operator.equals("@")) {
                bytecodeCompiler.throwCompilerException("splice requires array variable: splice @array, ...");
            }

            int arrayReg = -1;  // Will be assigned in if/else blocks

            if (arrayOp.operand instanceof IdentifierNode) {
                // splice @array
                String varName = "@" + ((IdentifierNode) arrayOp.operand).name;

                // Get the array - check lexical first, then global
                if (bytecodeCompiler.hasVariable(varName)) {
                    // Lexical array
                    arrayReg = bytecodeCompiler.getVariableRegister(varName);
                } else {
                    // Global array - load it
                    arrayReg = bytecodeCompiler.allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(
                            ((IdentifierNode) arrayOp.operand).name,
                            bytecodeCompiler.getCurrentPackage()
                    );
                    int nameIdx = bytecodeCompiler.addToStringPool(globalArrayName);
                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    bytecodeCompiler.emitReg(arrayReg);
                    bytecodeCompiler.emit(nameIdx);
                }
            } else if (arrayOp.operand instanceof OperatorNode) {
                // splice @$ref - dereference first
                arrayOp.operand.accept(bytecodeCompiler);
                int refReg = bytecodeCompiler.lastResultReg;

                // Dereference to get the array
                arrayReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                bytecodeCompiler.emitReg(arrayReg);
                bytecodeCompiler.emitReg(refReg);
            } else {
                bytecodeCompiler.throwCompilerException("splice requires array variable or dereferenced array: splice @array or splice @$ref");
            }

            // Create a list with the remaining arguments (offset, length, replacement values)
            // Compile each remaining argument and collect them into a RuntimeList
            List<Integer> argRegs = new ArrayList<>();
            for (int i = 1; i < list.elements.size(); i++) {
                list.elements.get(i).accept(bytecodeCompiler);
                argRegs.add(bytecodeCompiler.lastResultReg);
            }

            // Create a RuntimeList from these registers
            int argsListReg = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.CREATE_LIST);
            bytecodeCompiler.emitReg(argsListReg);
            bytecodeCompiler.emit(argRegs.size());
            for (int argReg : argRegs) {
                bytecodeCompiler.emitReg(argReg);
            }

            // Allocate result register
            int rd = bytecodeCompiler.allocateRegister();

            // Emit direct opcode SPLICE
            bytecodeCompiler.emit(Opcodes.SPLICE);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(arrayReg);
            bytecodeCompiler.emitReg(argsListReg);
            bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);  // Pass context for scalar/list conversion

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("reverse")) {
            // Array/string reverse: reverse @array or reverse $string
            // operand: ListNode containing arguments
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                bytecodeCompiler.throwCompilerException("reverse requires arguments");
            }

            ListNode list = (ListNode) node.operand;

            // Compile all arguments into registers
            List<Integer> argRegs = new ArrayList<>();
            for (Node arg : list.elements) {
                arg.accept(bytecodeCompiler);
                argRegs.add(bytecodeCompiler.lastResultReg);
            }

            // Create a RuntimeList from these registers
            int argsListReg = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.CREATE_LIST);
            bytecodeCompiler.emitReg(argsListReg);
            bytecodeCompiler.emit(argRegs.size());
            for (int argReg : argRegs) {
                bytecodeCompiler.emitReg(argReg);
            }

            // Allocate result register
            int rd = bytecodeCompiler.allocateRegister();

            // Emit direct opcode REVERSE
            bytecodeCompiler.emit(Opcodes.REVERSE);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argsListReg);
            bytecodeCompiler.emit(RuntimeContextType.LIST);  // Context

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("exists")) {
            // exists $hash{key} or exists $array[index]
            // operand: ListNode containing the hash/array access
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                bytecodeCompiler.throwCompilerException("exists requires an argument");
            }

            ListNode list = (ListNode) node.operand;
            if (list.elements.isEmpty()) {
                bytecodeCompiler.throwCompilerException("exists requires an argument");
            }

            Node arg = list.elements.get(0);

            // Handle hash access: $hash{key}
            if (arg instanceof BinaryOperatorNode && ((BinaryOperatorNode) arg).operator.equals("{")) {
                BinaryOperatorNode hashAccess = (BinaryOperatorNode) arg;

                // Get hash register (need to handle $hash{key} -> %hash)
                int hashReg;
                if (hashAccess.left instanceof OperatorNode) {
                    OperatorNode leftOp = (OperatorNode) hashAccess.left;
                    if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode) {
                        // Simple: exists $hash{key} -> get %hash
                        String varName = ((IdentifierNode) leftOp.operand).name;
                        String hashVarName = "%" + varName;

                        if (bytecodeCompiler.hasVariable(hashVarName)) {
                            // Lexical hash
                            hashReg = bytecodeCompiler.getVariableRegister(hashVarName);
                        } else {
                            // Global hash - load it
                            hashReg = bytecodeCompiler.allocateRegister();
                            String globalHashName = NameNormalizer.normalizeVariableName(
                                    varName,
                                    bytecodeCompiler.getCurrentPackage()
                            );
                            int nameIdx = bytecodeCompiler.addToStringPool(globalHashName);
                            bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_HASH);
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emit(nameIdx);
                        }
                    } else {
                        // Complex: dereference needed
                        leftOp.operand.accept(bytecodeCompiler);
                        int scalarReg = bytecodeCompiler.lastResultReg;

                        hashReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                        bytecodeCompiler.emitReg(hashReg);
                        bytecodeCompiler.emitReg(scalarReg);
                    }
                } else if (hashAccess.left instanceof BinaryOperatorNode) {
                    // Nested: exists $hash{outer}{inner}
                    hashAccess.left.accept(bytecodeCompiler);
                    int scalarReg = bytecodeCompiler.lastResultReg;

                    hashReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                    bytecodeCompiler.emitReg(hashReg);
                    bytecodeCompiler.emitReg(scalarReg);
                } else {
                    bytecodeCompiler.throwCompilerException("Hash access requires variable or expression on left side");
                    return;
                }

                // Compile key (right side contains HashLiteralNode)
                int keyReg;
                if (hashAccess.right instanceof HashLiteralNode) {
                    HashLiteralNode keyNode = (HashLiteralNode) hashAccess.right;
                    if (!keyNode.elements.isEmpty()) {
                        Node keyElement = keyNode.elements.get(0);
                        if (keyElement instanceof IdentifierNode) {
                            // Bareword key - autoquote
                            String keyString = ((IdentifierNode) keyElement).name;
                            keyReg = bytecodeCompiler.allocateRegister();
                            int keyIdx = bytecodeCompiler.addToStringPool(keyString);
                            bytecodeCompiler.emit(Opcodes.LOAD_STRING);
                            bytecodeCompiler.emitReg(keyReg);
                            bytecodeCompiler.emit(keyIdx);
                        } else {
                            // Expression key
                            keyElement.accept(bytecodeCompiler);
                            keyReg = bytecodeCompiler.lastResultReg;
                        }
                    } else {
                        bytecodeCompiler.throwCompilerException("Hash key required for exists");
                        return;
                    }
                } else {
                    hashAccess.right.accept(bytecodeCompiler);
                    keyReg = bytecodeCompiler.lastResultReg;
                }

                // Emit HASH_EXISTS
                int rd = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.HASH_EXISTS);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(hashReg);
                bytecodeCompiler.emitReg(keyReg);

                bytecodeCompiler.lastResultReg = rd;
            } else {
                // For now, use SLOW_OP for other cases (array exists, etc.)
                arg.accept(bytecodeCompiler);
                int argReg = bytecodeCompiler.lastResultReg;

                int rd = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.EXISTS);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(argReg);

                bytecodeCompiler.lastResultReg = rd;
            }
        } else if (op.equals("delete")) {
            // delete $hash{key} or delete @hash{@keys}
            // operand: ListNode containing the hash/array access
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                bytecodeCompiler.throwCompilerException("delete requires an argument");
            }

            ListNode list = (ListNode) node.operand;
            if (list.elements.isEmpty()) {
                bytecodeCompiler.throwCompilerException("delete requires an argument");
            }

            Node arg = list.elements.get(0);

            // Handle hash access: $hash{key} or hash slice delete: delete @hash{keys}
            if (arg instanceof BinaryOperatorNode && ((BinaryOperatorNode) arg).operator.equals("{")) {
                BinaryOperatorNode hashAccess = (BinaryOperatorNode) arg;

                // Check if it's a hash slice delete: delete @hash{keys}
                if (hashAccess.left instanceof OperatorNode) {
                    OperatorNode leftOp = (OperatorNode) hashAccess.left;
                    if (leftOp.operator.equals("@")) {
                        // Hash slice delete: delete @hash{'key1', 'key2'}
                        // Use SLOW_OP for slice delete
                        int hashReg;

                        if (leftOp.operand instanceof IdentifierNode) {
                            String varName = ((IdentifierNode) leftOp.operand).name;
                            String hashVarName = "%" + varName;

                            if (bytecodeCompiler.hasVariable(hashVarName)) {
                                hashReg = bytecodeCompiler.getVariableRegister(hashVarName);
                            } else {
                                hashReg = bytecodeCompiler.allocateRegister();
                                String globalHashName = NameNormalizer.normalizeVariableName(
                                        varName,
                                        bytecodeCompiler.getCurrentPackage()
                                );
                                int nameIdx = bytecodeCompiler.addToStringPool(globalHashName);
                                bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_HASH);
                                bytecodeCompiler.emitReg(hashReg);
                                bytecodeCompiler.emit(nameIdx);
                            }
                        } else {
                            bytecodeCompiler.throwCompilerException("Hash slice delete requires identifier");
                            return;
                        }

                        // Get keys from HashLiteralNode
                        if (!(hashAccess.right instanceof HashLiteralNode)) {
                            bytecodeCompiler.throwCompilerException("Hash slice delete requires HashLiteralNode");
                            return;
                        }
                        HashLiteralNode keysNode = (HashLiteralNode) hashAccess.right;

                        // Compile all keys
                        List<Integer> keyRegs = new ArrayList<>();
                        for (Node keyElement : keysNode.elements) {
                            if (keyElement instanceof IdentifierNode) {
                                String keyString = ((IdentifierNode) keyElement).name;
                                int keyReg = bytecodeCompiler.allocateRegister();
                                int keyIdx = bytecodeCompiler.addToStringPool(keyString);
                                bytecodeCompiler.emit(Opcodes.LOAD_STRING);
                                bytecodeCompiler.emitReg(keyReg);
                                bytecodeCompiler.emit(keyIdx);
                                keyRegs.add(keyReg);
                            } else {
                                keyElement.accept(bytecodeCompiler);
                                keyRegs.add(bytecodeCompiler.lastResultReg);
                            }
                        }

                        // Create RuntimeList from keys
                        int keysListReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.CREATE_LIST);
                        bytecodeCompiler.emitReg(keysListReg);
                        bytecodeCompiler.emit(keyRegs.size());
                        for (int keyReg : keyRegs) {
                            bytecodeCompiler.emitReg(keyReg);
                        }

                        // Use SLOW_OP for hash slice delete
                        int rd = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.HASH_SLICE_DELETE);
                        bytecodeCompiler.emitReg(rd);
                        bytecodeCompiler.emitReg(hashReg);
                        bytecodeCompiler.emitReg(keysListReg);

                        bytecodeCompiler.lastResultReg = rd;
                        return;
                    }
                }

                // Single key delete: delete $hash{key}
                // Get hash register (need to handle $hash{key} -> %hash)
                int hashReg;
                if (hashAccess.left instanceof OperatorNode) {
                    OperatorNode leftOp = (OperatorNode) hashAccess.left;
                    if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode) {
                        // Simple: delete $hash{key} -> get %hash
                        String varName = ((IdentifierNode) leftOp.operand).name;
                        String hashVarName = "%" + varName;

                        if (bytecodeCompiler.hasVariable(hashVarName)) {
                            // Lexical hash
                            hashReg = bytecodeCompiler.getVariableRegister(hashVarName);
                        } else {
                            // Global hash - load it
                            hashReg = bytecodeCompiler.allocateRegister();
                            String globalHashName = NameNormalizer.normalizeVariableName(
                                    varName,
                                    bytecodeCompiler.getCurrentPackage()
                            );
                            int nameIdx = bytecodeCompiler.addToStringPool(globalHashName);
                            bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_HASH);
                            bytecodeCompiler.emitReg(hashReg);
                            bytecodeCompiler.emit(nameIdx);
                        }
                    } else {
                        // Complex: dereference needed
                        leftOp.operand.accept(bytecodeCompiler);
                        int scalarReg = bytecodeCompiler.lastResultReg;

                        hashReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                        bytecodeCompiler.emitReg(hashReg);
                        bytecodeCompiler.emitReg(scalarReg);
                    }
                } else if (hashAccess.left instanceof BinaryOperatorNode) {
                    // Nested: delete $hash{outer}{inner}
                    hashAccess.left.accept(bytecodeCompiler);
                    int scalarReg = bytecodeCompiler.lastResultReg;

                    hashReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                    bytecodeCompiler.emitReg(hashReg);
                    bytecodeCompiler.emitReg(scalarReg);
                } else {
                    bytecodeCompiler.throwCompilerException("Hash access requires variable or expression on left side");
                    return;
                }

                // Compile key (right side contains HashLiteralNode)
                int keyReg;
                if (hashAccess.right instanceof HashLiteralNode) {
                    HashLiteralNode keyNode = (HashLiteralNode) hashAccess.right;
                    if (!keyNode.elements.isEmpty()) {
                        Node keyElement = keyNode.elements.get(0);
                        if (keyElement instanceof IdentifierNode) {
                            // Bareword key - autoquote
                            String keyString = ((IdentifierNode) keyElement).name;
                            keyReg = bytecodeCompiler.allocateRegister();
                            int keyIdx = bytecodeCompiler.addToStringPool(keyString);
                            bytecodeCompiler.emit(Opcodes.LOAD_STRING);
                            bytecodeCompiler.emitReg(keyReg);
                            bytecodeCompiler.emit(keyIdx);
                        } else {
                            // Expression key
                            keyElement.accept(bytecodeCompiler);
                            keyReg = bytecodeCompiler.lastResultReg;
                        }
                    } else {
                        bytecodeCompiler.throwCompilerException("Hash key required for delete");
                        return;
                    }
                } else {
                    hashAccess.right.accept(bytecodeCompiler);
                    keyReg = bytecodeCompiler.lastResultReg;
                }

                // Emit HASH_DELETE
                int rd = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.HASH_DELETE);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(hashReg);
                bytecodeCompiler.emitReg(keyReg);

                bytecodeCompiler.lastResultReg = rd;
            } else {
                // For now, use SLOW_OP for other cases (hash slice delete, array delete, etc.)
                arg.accept(bytecodeCompiler);
                int argReg = bytecodeCompiler.lastResultReg;

                int rd = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.DELETE);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(argReg);

                bytecodeCompiler.lastResultReg = rd;
            }
        } else if (op.equals("keys")) {
            // keys %hash
            // operand: hash variable (OperatorNode("%" ...) or other expression)
            if (node.operand == null) {
                bytecodeCompiler.throwCompilerException("keys requires a hash argument");
            }

            // Compile the hash operand in LIST context (to avoid scalar conversion)
            int savedContext = bytecodeCompiler.currentCallContext;
            bytecodeCompiler.currentCallContext = RuntimeContextType.LIST;
            try {
                node.operand.accept(bytecodeCompiler);
            } finally {
                bytecodeCompiler.currentCallContext = savedContext;
            }
            int hashReg = bytecodeCompiler.lastResultReg;

            // Emit HASH_KEYS
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.HASH_KEYS);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(hashReg);

            // keys() returns a list in list context, scalar count in scalar context
            // The RuntimeHash.keys() method returns a RuntimeList
            // In scalar context, convert to scalar (count)
            if (savedContext == RuntimeContextType.SCALAR) {
                int scalarReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.ARRAY_SIZE);
                bytecodeCompiler.emitReg(scalarReg);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.lastResultReg = scalarReg;
            } else {
                bytecodeCompiler.lastResultReg = rd;
            }
        } else if (op.equals("chop")) {
            // chop $x - remove last character, modifies argument in place
            // operand: ListNode containing scalar variable reference
            if (node.operand == null) {
                bytecodeCompiler.throwCompilerException("chop requires an argument");
            }

            // Extract the actual operand from ListNode if needed
            Node actualOperand = node.operand;
            if (actualOperand instanceof ListNode) {
                ListNode list = (ListNode) actualOperand;
                if (list.elements.isEmpty()) {
                    bytecodeCompiler.throwCompilerException("chop requires an argument");
                }
                actualOperand = list.elements.get(0);
            }

            // Compile the operand (should be an lvalue)
            actualOperand.accept(bytecodeCompiler);
            int scalarReg = bytecodeCompiler.lastResultReg;

            // Call chopScalar and store result back
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.CHOP);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(scalarReg);

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("values")) {
            // values %hash
            // operand: hash variable (OperatorNode("%" ...) or other expression)
            if (node.operand == null) {
                bytecodeCompiler.throwCompilerException("values requires a hash argument");
            }

            // Compile the hash operand in LIST context (to avoid scalar conversion)
            int savedContext = bytecodeCompiler.currentCallContext;
            bytecodeCompiler.currentCallContext = RuntimeContextType.LIST;
            try {
                node.operand.accept(bytecodeCompiler);
            } finally {
                bytecodeCompiler.currentCallContext = savedContext;
            }
            int hashReg = bytecodeCompiler.lastResultReg;

            // Emit HASH_VALUES
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.HASH_VALUES);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(hashReg);

            // values() returns a list in list context, scalar count in scalar context
            // The RuntimeHash.values() method returns a RuntimeList
            // In scalar context, convert to scalar (count)
            if (savedContext == RuntimeContextType.SCALAR) {
                int scalarReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.ARRAY_SIZE);
                bytecodeCompiler.emitReg(scalarReg);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.lastResultReg = scalarReg;
            } else {
                bytecodeCompiler.lastResultReg = rd;
            }
        } else if (op.equals("$#")) {
            // $#array - get last index of array (size - 1)
            // operand: array variable (OperatorNode("@" ...) or IdentifierNode)
            if (node.operand == null) {
                bytecodeCompiler.throwCompilerException("$# requires an array argument");
            }

            int arrayReg = -1;

            // Handle different operand types
            if (node.operand instanceof OperatorNode) {
                OperatorNode operandOp = (OperatorNode) node.operand;

                if (operandOp.operator.equals("@") && operandOp.operand instanceof IdentifierNode) {
                    // $#@array or $#array (both work)
                    String varName = "@" + ((IdentifierNode) operandOp.operand).name;

                    if (bytecodeCompiler.hasVariable(varName)) {
                        arrayReg = bytecodeCompiler.getVariableRegister(varName);
                    } else {
                        arrayReg = bytecodeCompiler.allocateRegister();
                        String globalArrayName = NameNormalizer.normalizeVariableName(
                                ((IdentifierNode) operandOp.operand).name,
                                bytecodeCompiler.getCurrentPackage()
                        );
                        int nameIdx = bytecodeCompiler.addToStringPool(globalArrayName);
                        bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                        bytecodeCompiler.emitReg(arrayReg);
                        bytecodeCompiler.emit(nameIdx);
                    }
                } else if (operandOp.operator.equals("$")) {
                    // $#$ref - dereference first
                    operandOp.accept(bytecodeCompiler);
                    int refReg = bytecodeCompiler.lastResultReg;

                    arrayReg = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                    bytecodeCompiler.emitReg(arrayReg);
                    bytecodeCompiler.emitReg(refReg);
                } else {
                    bytecodeCompiler.throwCompilerException("$# requires array variable or dereferenced array");
                }
            } else if (node.operand instanceof IdentifierNode) {
                // $#array (without @)
                String varName = "@" + ((IdentifierNode) node.operand).name;

                if (bytecodeCompiler.hasVariable(varName)) {
                    arrayReg = bytecodeCompiler.getVariableRegister(varName);
                } else {
                    arrayReg = bytecodeCompiler.allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(
                            ((IdentifierNode) node.operand).name,
                            bytecodeCompiler.getCurrentPackage()
                    );
                    int nameIdx = bytecodeCompiler.addToStringPool(globalArrayName);
                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    bytecodeCompiler.emitReg(arrayReg);
                    bytecodeCompiler.emit(nameIdx);
                }
            } else {
                bytecodeCompiler.throwCompilerException("$# requires array variable");
            }

            // Get array size
            int sizeReg = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.ARRAY_SIZE);
            bytecodeCompiler.emitReg(sizeReg);
            bytecodeCompiler.emitReg(arrayReg);

            // Subtract 1 to get last index
            int oneReg = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.LOAD_INT);
            bytecodeCompiler.emitReg(oneReg);
            bytecodeCompiler.emitInt(1);

            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.SUB_SCALAR);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(sizeReg);
            bytecodeCompiler.emitReg(oneReg);

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("length")) {
            // length($string) - get string length
            // operand: ListNode containing the string argument
            if (node.operand == null) {
                bytecodeCompiler.throwCompilerException("length requires an argument");
            }

            // Compile the operand
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (list.elements.isEmpty()) {
                    bytecodeCompiler.throwCompilerException("length requires an argument");
                }
                // Get first element
                list.elements.get(0).accept(bytecodeCompiler);
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int stringReg = bytecodeCompiler.lastResultReg;

            // Call length builtin using SLOW_OP
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.LENGTH_OP);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(stringReg);

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("open")) {
            // open(filehandle, mode, filename) or open(filehandle, expr)
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                bytecodeCompiler.throwCompilerException("open requires arguments");
            }

            ListNode argsList = (ListNode) node.operand;
            if (argsList.elements.isEmpty()) {
                bytecodeCompiler.throwCompilerException("open requires arguments");
            }

            // Compile all arguments into a list
            int argsReg = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.NEW_ARRAY);
            bytecodeCompiler.emitReg(argsReg);

            for (Node arg : argsList.elements) {
                arg.accept(bytecodeCompiler);
                int elemReg = bytecodeCompiler.lastResultReg;

                bytecodeCompiler.emit(Opcodes.ARRAY_PUSH);
                bytecodeCompiler.emitReg(argsReg);
                bytecodeCompiler.emitReg(elemReg);
            }

            // Call open with context and args
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.OPEN);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);
            bytecodeCompiler.emitReg(argsReg);

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("matchRegex")) {
            // m/pattern/flags - create a regex and optionally match against a string
            // operand: ListNode containing pattern, flags, and optionally string (from =~ binding)
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                bytecodeCompiler.throwCompilerException("matchRegex requires pattern and flags");
            }

            ListNode args = (ListNode) node.operand;
            if (args.elements.size() < 2) {
                bytecodeCompiler.throwCompilerException("matchRegex requires pattern and flags");
            }

            // Compile pattern
            args.elements.get(0).accept(bytecodeCompiler);
            int patternReg = bytecodeCompiler.lastResultReg;

            // Compile flags
            args.elements.get(1).accept(bytecodeCompiler);
            int flagsReg = bytecodeCompiler.lastResultReg;

            // Create quoted regex using QUOTE_REGEX opcode
            int regexReg = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.QUOTE_REGEX);
            bytecodeCompiler.emitReg(regexReg);
            bytecodeCompiler.emitReg(patternReg);
            bytecodeCompiler.emitReg(flagsReg);

            // Check if a string was provided (from =~ binding)
            if (args.elements.size() > 2) {
                // String provided - perform the match
                args.elements.get(2).accept(bytecodeCompiler);
                int stringReg = bytecodeCompiler.lastResultReg;

                // Call MATCH_REGEX to perform the match
                int rd = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.MATCH_REGEX);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(stringReg);
                bytecodeCompiler.emitReg(regexReg);
                bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);

                bytecodeCompiler.lastResultReg = rd;
            } else {
                // No string provided - just return the regex object
                bytecodeCompiler.lastResultReg = regexReg;
            }
        } else if (op.equals("replaceRegex")) {
            // s/pattern/replacement/flags - regex substitution
            // operand: ListNode containing [pattern, replacement, flags] or [pattern, replacement, flags, string]
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                bytecodeCompiler.throwCompilerException("replaceRegex requires pattern, replacement, and flags");
            }

            ListNode args = (ListNode) node.operand;
            if (args.elements.size() < 3) {
                bytecodeCompiler.throwCompilerException("replaceRegex requires pattern, replacement, and flags");
            }

            // Compile pattern
            args.elements.get(0).accept(bytecodeCompiler);
            int patternReg = bytecodeCompiler.lastResultReg;

            // Compile replacement
            args.elements.get(1).accept(bytecodeCompiler);
            int replacementReg = bytecodeCompiler.lastResultReg;

            // Compile flags
            args.elements.get(2).accept(bytecodeCompiler);
            int flagsReg = bytecodeCompiler.lastResultReg;

            // Create replacement regex using GET_REPLACEMENT_REGEX opcode
            int regexReg = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.GET_REPLACEMENT_REGEX);
            bytecodeCompiler.emitReg(regexReg);
            bytecodeCompiler.emitReg(patternReg);
            bytecodeCompiler.emitReg(replacementReg);
            bytecodeCompiler.emitReg(flagsReg);

            // Get the string to operate on (element 3 if provided, else $_)
            int stringReg;
            if (args.elements.size() > 3) {
                // String provided in operand list (from =~ binding)
                args.elements.get(3).accept(bytecodeCompiler);
                stringReg = bytecodeCompiler.lastResultReg;
            } else {
                // Use $_ as default
                String varName = "$_";
                if (bytecodeCompiler.hasVariable(varName)) {
                    stringReg = bytecodeCompiler.getVariableRegister(varName);
                } else {
                    stringReg = bytecodeCompiler.allocateRegister();
                    int nameIdx = bytecodeCompiler.addToStringPool("main::_");
                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                    bytecodeCompiler.emitReg(stringReg);
                    bytecodeCompiler.emit(nameIdx);
                }
            }

            // Apply the regex match (which handles replacement)
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.MATCH_REGEX);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(stringReg);
            bytecodeCompiler.emitReg(regexReg);
            bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("substr")) {
            // substr($string, $offset, $length, $replacement)
            // operand is a ListNode with 2-4 elements
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                bytecodeCompiler.throwCompilerException("substr requires arguments");
            }

            ListNode args = (ListNode) node.operand;
            if (args.elements.size() < 2) {
                bytecodeCompiler.throwCompilerException("substr requires at least 2 arguments");
            }

            // Compile arguments
            java.util.List<Integer> argRegs = new java.util.ArrayList<>();
            for (Node arg : args.elements) {
                arg.accept(bytecodeCompiler);
                argRegs.add(bytecodeCompiler.lastResultReg);
            }

            // Create list with arguments: CREATE_LIST rd count [regs...]
            int argsListReg = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.CREATE_LIST);
            bytecodeCompiler.emitReg(argsListReg);
            bytecodeCompiler.emit(argRegs.size());  // emit count
            for (int argReg : argRegs) {
                bytecodeCompiler.emitReg(argReg);
            }

            // Call SUBSTR_VAR opcode
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.SUBSTR_VAR);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argsListReg);
            bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("chomp")) {
            // chomp($x) or chomp - remove trailing newlines
            if (node.operand == null) {
                // chomp with no args - operates on $_
                String varName = "$_";
                int targetReg;
                if (bytecodeCompiler.hasVariable(varName)) {
                    targetReg = bytecodeCompiler.getVariableRegister(varName);
                } else {
                    targetReg = bytecodeCompiler.allocateRegister();
                    int nameIdx = bytecodeCompiler.addToStringPool("main::_");
                    bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                    bytecodeCompiler.emitReg(targetReg);
                    bytecodeCompiler.emit(nameIdx);
                }

                int rd = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.CHOMP);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(targetReg);

                bytecodeCompiler.lastResultReg = rd;
            } else {
                // chomp with argument
                if (node.operand instanceof ListNode) {
                    ListNode list = (ListNode) node.operand;
                    if (!list.elements.isEmpty()) {
                        list.elements.get(0).accept(bytecodeCompiler);
                    } else {
                        bytecodeCompiler.throwCompilerException("chomp requires an argument");
                    }
                } else {
                    node.operand.accept(bytecodeCompiler);
                }
                int targetReg = bytecodeCompiler.lastResultReg;

                int rd = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.CHOMP);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(targetReg);

                bytecodeCompiler.lastResultReg = rd;
            }
        } else if (op.equals("+")) {
            // Unary + operator: forces numeric context on its operand
            // For arrays/hashes in scalar context, this returns the size
            // For scalars, this ensures the value is numeric
            if (node.operand != null) {
                // Evaluate operand in scalar context
                int savedContext = bytecodeCompiler.currentCallContext;
                bytecodeCompiler.currentCallContext = RuntimeContextType.SCALAR;
                try {
                    node.operand.accept(bytecodeCompiler);
                    int operandReg = bytecodeCompiler.lastResultReg;

                    // Emit ARRAY_SIZE to convert to scalar
                    // This handles arrays/hashes (converts to size) and passes through scalars
                    int rd = bytecodeCompiler.allocateRegister();
                    bytecodeCompiler.emit(Opcodes.ARRAY_SIZE);
                    bytecodeCompiler.emitReg(rd);
                    bytecodeCompiler.emitReg(operandReg);

                    bytecodeCompiler.lastResultReg = rd;
                } finally {
                    bytecodeCompiler.currentCallContext = savedContext;
                }
            } else {
                bytecodeCompiler.throwCompilerException("unary + operator requires an operand");
            }
        } else if (op.equals("wantarray")) {
            // wantarray operator: returns undef in VOID, false in SCALAR, true in LIST
            // Read register 2 (wantarray context) and convert to Perl convention
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.WANTARRAY);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(2);  // Register 2 contains the calling context

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("sprintf")) {
            // sprintf($format, @args) - SprintfOperator.sprintf
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (list.elements.isEmpty()) {
                    bytecodeCompiler.throwCompilerException("sprintf requires a format argument");
                }

                // First argument is the format string
                list.elements.get(0).accept(bytecodeCompiler);
                int formatReg = bytecodeCompiler.lastResultReg;

                // Compile remaining arguments and collect their registers
                java.util.List<Integer> argRegs = new java.util.ArrayList<>();
                for (int i = 1; i < list.elements.size(); i++) {
                    list.elements.get(i).accept(bytecodeCompiler);
                    argRegs.add(bytecodeCompiler.lastResultReg);
                }

                // Create a RuntimeList with the arguments
                int listReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.CREATE_LIST);
                bytecodeCompiler.emitReg(listReg);
                for (int argReg : argRegs) {
                    bytecodeCompiler.emitReg(argReg);
                }

                // Call sprintf with format and arg list
                int rd = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.SPRINTF);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(formatReg);
                bytecodeCompiler.emitReg(listReg);
                bytecodeCompiler.lastResultReg = rd;
            } else {
                bytecodeCompiler.throwCompilerException("sprintf requires arguments");
            }
            // GENERATED_OPERATORS_START
        } else if (op.equals("int")) {
            // int($x) - MathOperators.integer
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("int requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.INT);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("log")) {
            // log($x) - MathOperators.log
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("log requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.LOG);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("sqrt")) {
            // sqrt($x) - MathOperators.sqrt
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("sqrt requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.SQRT);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("cos")) {
            // cos($x) - MathOperators.cos
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("cos requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.COS);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("sin")) {
            // sin($x) - MathOperators.sin
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("sin requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.SIN);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("exp")) {
            // exp($x) - MathOperators.exp
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("exp requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.EXP);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("abs")) {
            // abs($x) - MathOperators.abs
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("abs requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.ABS);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("binary~")) {
            // binary~($x) - BitwiseOperators.bitwiseNotBinary
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("binary~ requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.BINARY_NOT);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("integerBitwiseNot")) {
            // integerBitwiseNot($x) - BitwiseOperators.integerBitwiseNot
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("integerBitwiseNot requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.INTEGER_BITWISE_NOT);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("ord")) {
            // ord($x) - ScalarOperators.ord
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("ord requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.ORD);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("ordBytes")) {
            // ordBytes($x) - ScalarOperators.ordBytes
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("ordBytes requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.ORD_BYTES);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("oct")) {
            // oct($x) - ScalarOperators.oct
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("oct requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.OCT);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("hex")) {
            // hex($x) - ScalarOperators.hex
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("hex requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.HEX);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("srand")) {
            // srand($x) - Random.srand
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("srand requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.SRAND);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("chr")) {
            // chr($x) - StringOperators.chr
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("chr requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.CHR);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("chrBytes")) {
            // chrBytes($x) - StringOperators.chrBytes
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("chrBytes requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.CHR_BYTES);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("lengthBytes")) {
            // lengthBytes($x) - StringOperators.lengthBytes
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("lengthBytes requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.LENGTH_BYTES);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("quotemeta")) {
            // quotemeta($x) - StringOperators.quotemeta
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("quotemeta requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.QUOTEMETA);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("fc")) {
            // fc($x) - StringOperators.fc
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("fc requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.FC);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("lc")) {
            // lc($x) - StringOperators.lc
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("lc requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.LC);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("lcfirst")) {
            // lcfirst($x) - StringOperators.lcfirst
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("lcfirst requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.LCFIRST);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("uc")) {
            // uc($x) - StringOperators.uc
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("uc requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.UC);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("ucfirst")) {
            // ucfirst($x) - StringOperators.ucfirst
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("ucfirst requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.UCFIRST);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("sleep")) {
            // sleep($x) - Time.sleep
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("sleep requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.SLEEP);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("tell")) {
            // tell($x) - IOOperator.tell
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("tell requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.TELL);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("rmdir")) {
            // rmdir($x) - Directory.rmdir
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("rmdir requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.RMDIR);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("closedir")) {
            // closedir($x) - Directory.closedir
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("closedir requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.CLOSEDIR);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("rewinddir")) {
            // rewinddir($x) - Directory.rewinddir
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("rewinddir requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.REWINDDIR);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("telldir")) {
            // telldir($x) - Directory.telldir
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("telldir requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.TELLDIR);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("chdir")) {
            // chdir($x) - Directory.chdir
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("chdir requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.CHDIR);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("exit")) {
            // exit($x) - WarnDie.exit
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (!list.elements.isEmpty()) {
                    list.elements.get(0).accept(bytecodeCompiler);
                } else {
                    bytecodeCompiler.throwCompilerException("exit requires an argument");
                }
            } else {
                node.operand.accept(bytecodeCompiler);
            }
            int argReg = bytecodeCompiler.lastResultReg;
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.EXIT);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(argReg);
            bytecodeCompiler.lastResultReg = rd;
            // GENERATED_OPERATORS_END
        } else if (op.equals("tr") || op.equals("y")) {
            // tr/// or y/// transliteration operator
            // Pattern: tr/search/replace/modifiers on $variable
            if (!(node.operand instanceof ListNode)) {
                bytecodeCompiler.throwCompilerException("tr operator requires list operand");
            }

            ListNode list = (ListNode) node.operand;
            if (list.elements.size() < 3) {
                bytecodeCompiler.throwCompilerException("tr operator requires search, replace, and modifiers");
            }

            // Compile search pattern
            list.elements.get(0).accept(bytecodeCompiler);
            int searchReg = bytecodeCompiler.lastResultReg;

            // Compile replace pattern
            list.elements.get(1).accept(bytecodeCompiler);
            int replaceReg = bytecodeCompiler.lastResultReg;

            // Compile modifiers
            list.elements.get(2).accept(bytecodeCompiler);
            int modifiersReg = bytecodeCompiler.lastResultReg;

            // Compile target variable (element [3] or default to $_)
            int targetReg;
            if (list.elements.size() > 3 && list.elements.get(3) != null) {
                list.elements.get(3).accept(bytecodeCompiler);
                targetReg = bytecodeCompiler.lastResultReg;
            } else {
                // Default to $_ - need to load it
                targetReg = bytecodeCompiler.allocateRegister();
                String underscoreName = NameNormalizer.normalizeVariableName("_", bytecodeCompiler.getCurrentPackage());
                int nameIdx = bytecodeCompiler.addToStringPool(underscoreName);
                bytecodeCompiler.emit(Opcodes.LOAD_GLOBAL_SCALAR);
                bytecodeCompiler.emitReg(targetReg);
                bytecodeCompiler.emit(nameIdx);
            }

            // Emit TR_TRANSLITERATE operation
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emit(Opcodes.TR_TRANSLITERATE);
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.emitReg(searchReg);
            bytecodeCompiler.emitReg(replaceReg);
            bytecodeCompiler.emitReg(modifiersReg);
            bytecodeCompiler.emitReg(targetReg);
            bytecodeCompiler.emitInt(bytecodeCompiler.currentCallContext);

            bytecodeCompiler.lastResultReg = rd;
        } else if (op.equals("tie") || op.equals("untie") || op.equals("tied")) {
            // tie($var, $classname, @args) or untie($var) or tied($var)
            // Compile all arguments as a list
            if (node.operand != null) {
                node.operand.accept(bytecodeCompiler);
                int argsReg = bytecodeCompiler.lastResultReg;

                int rd = bytecodeCompiler.allocateRegister();
                short opcode = switch (op) {
                    case "tie" -> Opcodes.TIE;
                    case "untie" -> Opcodes.UNTIE;
                    case "tied" -> Opcodes.TIED;
                    default -> throw new IllegalStateException("Unexpected operator: " + op);
                };
                bytecodeCompiler.emitWithToken(opcode, node.getIndex());
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(argsReg);
                bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);

                bytecodeCompiler.lastResultReg = rd;
            } else {
                bytecodeCompiler.throwCompilerException(op + " requires arguments");
            }
        } else if (op.equals("getppid")) {
            // getppid() - returns parent process ID
            // Format: GETPPID rd
            int rd = bytecodeCompiler.allocateRegister();
            bytecodeCompiler.emitWithToken(Opcodes.GETPPID, node.getIndex());
            bytecodeCompiler.emitReg(rd);
            bytecodeCompiler.lastResultReg = rd;
        } else {
            bytecodeCompiler.throwCompilerException("Unsupported operator: " + op);
        }
    }
}
