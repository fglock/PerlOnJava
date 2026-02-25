package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.operators.*;
import org.perlonjava.runtime.operators.ChownOperator;
import org.perlonjava.runtime.operators.Directory;
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

        // EACH receives the container directly (RuntimeHash or RuntimeArray), not a RuntimeList
        if (opcode == Opcodes.EACH) {
            registers[rd] = registers[argsReg].each(ctx);
            return pc;
        }

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
            // EACH is handled above before the RuntimeList cast
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
            case Opcodes.OPENDIR -> Directory.opendir(args);
            case Opcodes.READDIR -> Directory.readdir(args.elements.isEmpty() ? null : (RuntimeScalar) args.elements.get(0), ctx);
            case Opcodes.SEEKDIR -> Directory.seekdir(args);
            default -> throw new IllegalStateException("Unknown opcode in MiscOpcodeHandler: " + opcode);
        };

        registers[rd] = result;
        return pc;
    }
}
