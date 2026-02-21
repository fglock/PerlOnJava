package org.perlonjava.interpreter;

import org.perlonjava.nativ.NativeUtils;
import org.perlonjava.operators.*;
import org.perlonjava.runtime.*;

/**
 * Handler for miscellaneous opcodes that call runtime operator methods.
 * These operators are typically used in eval interpreter mode.
 */
public class MiscOpcodeHandler {

    /**
     * Execute miscellaneous operators that take arguments and call runtime methods.
     * Format: OPCODE rd argsReg ctx
     */
    public static int execute(short opcode, short[] bytecode, int pc, RuntimeBase[] registers) {
        int rd = bytecode[pc++];
        int argsReg = bytecode[pc++];
        int ctx = bytecode[pc++];

        RuntimeList args = (RuntimeList) registers[argsReg];
        RuntimeBase[] argsArray = args.elements.toArray(new RuntimeBase[0]);

        RuntimeBase result = switch (opcode) {
            case Opcodes.CHMOD -> Operator.chmod(args);
            case Opcodes.UNLINK -> UnlinkOperator.unlink(ctx, argsArray);
            case Opcodes.UTIME -> UtimeOperator.utime(ctx, argsArray);
            case Opcodes.RENAME -> Operator.rename(ctx, argsArray);
            case Opcodes.LINK -> NativeUtils.link(ctx, argsArray);
            case Opcodes.READLINK -> Operator.readlink(ctx, argsArray);
            case Opcodes.UMASK -> UmaskOperator.umask(ctx, argsArray);
            case Opcodes.GETC -> IOOperator.getc(ctx, argsArray);
            case Opcodes.FILENO -> IOOperator.fileno(ctx, argsArray);
            case Opcodes.QX -> {
                // qx not implemented yet - return empty string
                yield new RuntimeScalar("");
            }
            case Opcodes.SYSTEM -> SystemOperator.system(args, false, ctx);
            case Opcodes.CALLER -> RuntimeCode.caller(args, ctx);
            case Opcodes.EACH -> {
                // EACH needs the container directly, not wrapped in a list
                // The argsReg should contain a list with one element (the container)
                if (!args.elements.isEmpty()) {
                    RuntimeBase container = args.elements.get(0);
                    // Call each on the container
                    yield container.each(ctx);
                } else {
                    throw new RuntimeException("each requires a hash or array argument");
                }
            }
            case Opcodes.PACK -> Pack.pack(args);
            case Opcodes.VEC -> Vec.vec(args);
            case Opcodes.LOCALTIME -> Time.localtime(args, ctx);
            case Opcodes.GMTIME -> Time.gmtime(args, ctx);
            case Opcodes.CRYPT -> Crypt.crypt(args);
            default -> throw new IllegalStateException("Unknown opcode in MiscOpcodeHandler: " + opcode);
        };

        registers[rd] = result;
        return pc;
    }
}
