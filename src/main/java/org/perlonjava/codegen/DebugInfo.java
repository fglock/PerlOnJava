package org.perlonjava.codegen;

import org.objectweb.asm.Label;

public class DebugInfo {
    static void setDebugInfoLineNumber(EmitterContext ctx, int tokenIndex) {
        // Annotate the bytecode with Perl source code line numbers
        int lineNumber = ctx.errorUtil.getLineNumber(tokenIndex);
        ctx.logDebug("Line number " + lineNumber);
        Label thisLabel = new Label();
        ctx.mv.visitLabel(thisLabel);
        ctx.mv.visitLineNumber(lineNumber, thisLabel); // Associate line number with thisLabel
    }

    static void setDebugInfoFileName(EmitterContext ctx) {
        // Set the source file name. This is used for runtime error messages
        String packageName = ctx.symbolTable.getCurrentPackage();
        String sourceFileName = ctx.compilerOptions.fileName;
        // System.out.println("Source file name: " + sourceFileName + " @ " + packageName + " " + ctx.cw);
        ctx.cw.visitSource(sourceFileName + " @ " + packageName, null);
    }
}
