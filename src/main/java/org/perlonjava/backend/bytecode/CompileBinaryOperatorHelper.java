package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

public class CompileBinaryOperatorHelper {
    /**
     * Helper method to compile binary operator switch statement.
     * Extracted from visit(BinaryOperatorNode) to reduce method size.
     * Handles the giant switch statement for all binary operators.
     *
     * @param bytecodeCompiler
     * @param operator         The binary operator string
     * @param rs1              Left operand register
     * @param rs2              Right operand register
     * @param tokenIndex       Token index for error reporting
     * @return Result register containing the operation result
     */
    public static int compileBinaryOperatorSwitch(BytecodeCompiler bytecodeCompiler, String operator, int rs1, int rs2, int tokenIndex) {
        // Allocate result register
        int rd = bytecodeCompiler.allocateRegister();

        // Emit opcode based on operator
        switch (operator) {
            case "+" -> {
                bytecodeCompiler.emit(Opcodes.ADD_SCALAR);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "-" -> {
                bytecodeCompiler.emit(Opcodes.SUB_SCALAR);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "*" -> {
                bytecodeCompiler.emit(Opcodes.MUL_SCALAR);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "%" -> {
                bytecodeCompiler.emit(Opcodes.MOD_SCALAR);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "/" -> {
                bytecodeCompiler.emit(Opcodes.DIV_SCALAR);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "**" -> {
                bytecodeCompiler.emit(Opcodes.POW_SCALAR);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "." -> {
                bytecodeCompiler.emit(Opcodes.CONCAT);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "x" -> {
                bytecodeCompiler.emit(Opcodes.REPEAT);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "<=>" -> {
                bytecodeCompiler.emit(Opcodes.COMPARE_NUM);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "cmp" -> {
                bytecodeCompiler.emit(Opcodes.COMPARE_STR);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "bless" -> {
                // bless $ref, "Package" or bless $ref (defaults to current package)
                // rs1 = reference to bless
                // rs2 = package name (or undef for current package)
                bytecodeCompiler.emit(Opcodes.BLESS);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "isa" -> {
                // $obj isa "Package" - check if object is instance of package
                // rs1 = object/reference
                // rs2 = package name
                bytecodeCompiler.emit(Opcodes.ISA);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "==" -> {
                bytecodeCompiler.emit(Opcodes.EQ_NUM);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "<" -> {
                bytecodeCompiler.emit(Opcodes.LT_NUM);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case ">" -> {
                bytecodeCompiler.emit(Opcodes.GT_NUM);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "<=" -> {
                bytecodeCompiler.emit(Opcodes.LE_NUM);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case ">=" -> {
                bytecodeCompiler.emit(Opcodes.GE_NUM);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "!=" -> {
                bytecodeCompiler.emit(Opcodes.NE_NUM);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "eq" -> {
                // String equality: $a eq $b
                bytecodeCompiler.emit(Opcodes.EQ_STR);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "ne" -> {
                // String inequality: $a ne $b
                bytecodeCompiler.emit(Opcodes.NE_STR);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "lt", "gt", "le", "ge" -> {
                // String comparisons using COMPARE_STR (like cmp)
                // cmp returns: -1 if $a lt $b, 0 if equal, 1 if $a gt $b
                int cmpReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.COMPARE_STR);
                bytecodeCompiler.emitReg(cmpReg);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);

                // Compare result to 0
                int zeroReg = bytecodeCompiler.allocateRegister();
                bytecodeCompiler.emit(Opcodes.LOAD_INT);
                bytecodeCompiler.emitReg(zeroReg);
                bytecodeCompiler.emitInt(0);

                // Emit appropriate comparison
                switch (operator) {
                    case "lt" -> bytecodeCompiler.emit(Opcodes.LT_NUM);  // cmp < 0
                    case "gt" -> bytecodeCompiler.emit(Opcodes.GT_NUM);  // cmp > 0
                    case "le" -> {
                        // le: cmp <= 0, which is !(cmp > 0)
                        int gtReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.GT_NUM);
                        bytecodeCompiler.emitReg(gtReg);
                        bytecodeCompiler.emitReg(cmpReg);
                        bytecodeCompiler.emitReg(zeroReg);
                        bytecodeCompiler.emit(Opcodes.NOT);
                        bytecodeCompiler.emitReg(rd);
                        bytecodeCompiler.emitReg(gtReg);
                        return rd;
                    }
                    case "ge" -> {
                        // ge: cmp >= 0, which is !(cmp < 0)
                        int ltReg = bytecodeCompiler.allocateRegister();
                        bytecodeCompiler.emit(Opcodes.LT_NUM);
                        bytecodeCompiler.emitReg(ltReg);
                        bytecodeCompiler.emitReg(cmpReg);
                        bytecodeCompiler.emitReg(zeroReg);
                        bytecodeCompiler.emit(Opcodes.NOT);
                        bytecodeCompiler.emitReg(rd);
                        bytecodeCompiler.emitReg(ltReg);
                        return rd;
                    }
                }
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(cmpReg);
                bytecodeCompiler.emitReg(zeroReg);
            }
            case "(", "()" -> {
                // Apply operator: $coderef->(args) or &subname(args) or foo(args)
                // left (rs1) = code reference (RuntimeScalar containing RuntimeCode or SubroutineNode)
                // right (rs2) = arguments (should be RuntimeList from ListNode)

                // Note: rs2 should contain a RuntimeList (from visiting the ListNode)
                // We need to convert it to RuntimeArray for the CALL_SUB opcode

                // For now, rs2 is a RuntimeList - we'll pass it directly and let
                // BytecodeInterpreter convert it to RuntimeArray

                // Emit CALL_SUB: rd = coderef.apply(args, context)
                bytecodeCompiler.emit(Opcodes.CALL_SUB);
                bytecodeCompiler.emitReg(rd);  // Result register
                bytecodeCompiler.emitReg(rs1); // Code reference register
                bytecodeCompiler.emitReg(rs2); // Arguments register (RuntimeList to be converted to RuntimeArray)
                bytecodeCompiler.emit(bytecodeCompiler.currentCallContext); // Use current calling context

                // Note: CALL_SUB may return RuntimeControlFlowList
                // The interpreter will handle control flow propagation
            }
            case ".." -> {
                // Range operator: start..end
                // Create a PerlRange object which can be iterated or converted to a list

                // Optimization: if both operands are constant numbers, create range at compile time
                // (This optimization would need access to the original nodes, which we don't have here)
                // So we always use runtime range creation

                // Runtime range creation using RANGE opcode
                // rs1 and rs2 already contain the start and end values
                bytecodeCompiler.emit(Opcodes.RANGE);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "map" -> {
                // Map operator: map { block } list
                // rs1 = closure (SubroutineNode compiled to code reference)
                // rs2 = list expression

                // Emit MAP opcode
                bytecodeCompiler.emit(Opcodes.MAP);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs2);       // List register
                bytecodeCompiler.emitReg(rs1);       // Closure register
                bytecodeCompiler.emit(RuntimeContextType.LIST);  // Map always uses list context
            }
            case "grep" -> {
                // Grep operator: grep { block } list
                // rs1 = closure (SubroutineNode compiled to code reference)
                // rs2 = list expression

                // Emit GREP opcode
                bytecodeCompiler.emit(Opcodes.GREP);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs2);       // List register
                bytecodeCompiler.emitReg(rs1);       // Closure register
                bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);  // Use current context
            }
            case "sort" -> {
                // Sort operator: sort { block } list
                // rs1 = closure (SubroutineNode compiled to code reference)
                // rs2 = list expression

                // Emit SORT opcode
                bytecodeCompiler.emit(Opcodes.SORT);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs2);       // List register
                bytecodeCompiler.emitReg(rs1);       // Closure register
                bytecodeCompiler.emitInt(bytecodeCompiler.addToStringPool(bytecodeCompiler.getCurrentPackage()));  // Package name for sort
            }
            case "split" -> {
                // Split operator: split pattern, string
                // rs1 = pattern (string or regex)
                // rs2 = list containing string to split (and optional limit)

                // Emit direct opcode SPLIT
                bytecodeCompiler.emit(Opcodes.SPLIT);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);  // Pattern register
                bytecodeCompiler.emitReg(rs2);  // Args register
                bytecodeCompiler.emit(RuntimeContextType.LIST);  // Split uses list context
            }
            case "[" -> {
                // Array element access: $a[10] means get element 10 from array @a
                // Array slice: @a[1,2,3] or @a[1..3] means get multiple elements
                // Also handles multidimensional: $a[0][1] means $a[0]->[1]
                // This case should NOT be reached because array access is handled specially before this switch
                bytecodeCompiler.throwCompilerException("Array access [ should be handled before switch", tokenIndex);
            }
            case "{" -> {
                // Hash element access: $h{key} means get element 'key' from hash %h
                // Hash slice access: @h{keys} returns multiple values as array
                // This case should NOT be reached because hash access is handled specially before this switch
                bytecodeCompiler.throwCompilerException("Hash access { should be handled before switch", tokenIndex);
            }
            case "push" -> {
                // This should NOT be reached because push is handled specially before this switch
                bytecodeCompiler.throwCompilerException("push should be handled before switch", tokenIndex);
            }
            case "unshift" -> {
                // This should NOT be reached because unshift is handled specially before this switch
                bytecodeCompiler.throwCompilerException("unshift should be handled before switch", tokenIndex);
            }
            case "+=" -> {
                // This should NOT be reached because += is handled specially before this switch
                bytecodeCompiler.throwCompilerException("+= should be handled before switch", tokenIndex);
            }
            case "-=", "*=", "/=", "%=", ".=" -> {
                // This should NOT be reached because compound assignments are handled specially before this switch
                bytecodeCompiler.throwCompilerException(operator + " should be handled before switch", tokenIndex);
            }
            case "readline" -> {
                // <$fh> - read line from filehandle
                // rs1 = filehandle (or undef for ARGV)
                // rs2 = unused (ListNode)
                bytecodeCompiler.emit(Opcodes.READLINE);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);
            }
            case "=~" -> {
                // $string =~ /pattern/ - regex match
                // rs1 = string to match against
                // rs2 = compiled regex pattern
                bytecodeCompiler.emit(Opcodes.MATCH_REGEX);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
                bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);
            }
            case "!~" -> {
                // $string !~ /pattern/ - negated regex match
                // rs1 = string to match against
                // rs2 = compiled regex pattern
                bytecodeCompiler.emit(Opcodes.MATCH_REGEX_NOT);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
                bytecodeCompiler.emit(bytecodeCompiler.currentCallContext);
            }
            case "&" -> {
                // Numeric bitwise AND (default): rs1 & rs2
                bytecodeCompiler.emit(Opcodes.BITWISE_AND_BINARY);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "binary&" -> {
                // Numeric bitwise AND (use integer): rs1 binary& rs2
                // Same as & but explicitly numeric
                bytecodeCompiler.emit(Opcodes.BITWISE_AND_BINARY);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "|" -> {
                // Numeric bitwise OR (default): rs1 | rs2
                bytecodeCompiler.emit(Opcodes.BITWISE_OR_BINARY);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "binary|" -> {
                // Numeric bitwise OR (use integer): rs1 binary| rs2
                // Same as | but explicitly numeric
                bytecodeCompiler.emit(Opcodes.BITWISE_OR_BINARY);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "^" -> {
                // Numeric bitwise XOR (default): rs1 ^ rs2
                bytecodeCompiler.emit(Opcodes.BITWISE_XOR_BINARY);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "binary^" -> {
                // Numeric bitwise XOR (use integer): rs1 binary^ rs2
                // Same as ^ but explicitly numeric
                bytecodeCompiler.emit(Opcodes.BITWISE_XOR_BINARY);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "&." -> {
                // String bitwise AND: rs1 &. rs2
                bytecodeCompiler.emit(Opcodes.STRING_BITWISE_AND);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "|." -> {
                // String bitwise OR: rs1 |. rs2
                bytecodeCompiler.emit(Opcodes.STRING_BITWISE_OR);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "^." -> {
                // String bitwise XOR: rs1 ^. rs2
                bytecodeCompiler.emit(Opcodes.STRING_BITWISE_XOR);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "<<" -> {
                // Left shift: rs1 << rs2
                bytecodeCompiler.emit(Opcodes.LEFT_SHIFT);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case ">>" -> {
                // Right shift: rs1 >> rs2
                bytecodeCompiler.emit(Opcodes.RIGHT_SHIFT);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            case "..." -> {
                // Flip-flop operator (.. and ...) - per-call-site state via unique ID
                // Note: numeric range (..) is handled earlier in visitBinaryOperator for list context;
                // this case handles scalar-context flip-flop.
                int flipFlopId = org.perlonjava.runtime.operators.ScalarFlipFlopOperator.currentId++;
                org.perlonjava.runtime.operators.ScalarFlipFlopOperator op =
                    new org.perlonjava.runtime.operators.ScalarFlipFlopOperator(operator.equals("..."));
                org.perlonjava.runtime.operators.ScalarFlipFlopOperator.flipFlops.putIfAbsent(flipFlopId, op);
                bytecodeCompiler.emit(Opcodes.FLIP_FLOP);
                bytecodeCompiler.emitReg(rd);
                bytecodeCompiler.emit(flipFlopId);
                bytecodeCompiler.emitReg(rs1);
                bytecodeCompiler.emitReg(rs2);
            }
            default -> bytecodeCompiler.throwCompilerException("Unsupported operator: " + operator, tokenIndex);
        }

        return rd;
    }
}
