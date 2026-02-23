package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.operators.*;
import org.perlonjava.runtime.operators.ChownOperator;
import org.perlonjava.runtime.operators.WaitpidOperator;
import org.perlonjava.runtime.operators.Unpack;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * Handler for miscellaneous opcodes that call runtime operator methods.
 * These operators are typically used in eval interpreter mode.
 */
public class MiscOpcodeHandler {

    /**
     * Execute miscellaneous operators that take arguments and call runtime methods.
     * Format: OPCODE rd argsReg ctx
     */
    public static int execute(int opcode, int[] bytecode, int pc, RuntimeBase[] registers) {
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
            case Opcodes.UNPACK -> Unpack.unpack(ctx, argsArray);
            case Opcodes.VEC -> Vec.vec(args);
            case Opcodes.LOCALTIME -> Time.localtime(args, ctx);
            case Opcodes.GMTIME -> Time.gmtime(args, ctx);
            case Opcodes.CRYPT -> Crypt.crypt(args);
            // I/O operators
            case Opcodes.CLOSE -> IOOperator.close(ctx, argsArray);
            case Opcodes.BINMODE -> IOOperator.binmode(ctx, argsArray);
            case Opcodes.SEEK -> IOOperator.seek(ctx, argsArray);
            case Opcodes.EOF_OP -> IOOperator.eof(ctx, argsArray);
            case Opcodes.SYSREAD -> IOOperator.sysread(ctx, argsArray);
            case Opcodes.SYSWRITE -> IOOperator.syswrite(ctx, argsArray);
            case Opcodes.SYSOPEN -> IOOperator.sysopen(ctx, argsArray);
            case Opcodes.SOCKET -> IOOperator.socket(ctx, argsArray);
            case Opcodes.BIND -> IOOperator.bind(ctx, argsArray);
            case Opcodes.CONNECT -> IOOperator.connect(ctx, argsArray);
            case Opcodes.LISTEN -> IOOperator.listen(ctx, argsArray);
            case Opcodes.WRITE -> IOOperator.write(ctx, argsArray);
            case Opcodes.FORMLINE -> IOOperator.formline(ctx, argsArray);
            case Opcodes.PRINTF -> IOOperator.printf(ctx, argsArray);
            case Opcodes.ACCEPT -> IOOperator.accept(ctx, argsArray);
            case Opcodes.SYSSEEK -> IOOperator.sysseek(ctx, argsArray);
            case Opcodes.TRUNCATE -> IOOperator.truncate(ctx, argsArray);
            case Opcodes.READ -> IOOperator.read(ctx, argsArray);
            case Opcodes.CHOWN -> ChownOperator.chown(ctx, argsArray);
            case Opcodes.WAITPID -> WaitpidOperator.waitpid(ctx, argsArray);
            case Opcodes.SETSOCKOPT -> IOOperator.setsockopt(ctx, argsArray);
            case Opcodes.GETSOCKOPT -> IOOperator.getsockopt(ctx, argsArray);
            case Opcodes.GETPGRP -> Operator.getpgrp(ctx, argsArray);
            case Opcodes.SETPGRP -> Operator.setpgrp(ctx, argsArray);
            case Opcodes.GETPRIORITY -> Operator.getpriority(ctx, argsArray);
            case Opcodes.SETPRIORITY -> new RuntimeScalar(0); // stub - no native impl yet
            default -> throw new IllegalStateException("Unknown opcode in MiscOpcodeHandler: " + opcode);
        };

        registers[rd] = result;
        return pc;
    }
}
