package org.perlonjava.codegen;

import org.objectweb.asm.Label;

public class DebugInfo {
    public static final String PACKAGE_SEPARATOR = " @ ";

    public static record SourceLocation(String sourceFileName, String packageName, int lineNumber) {}

    public static SourceLocation parseStackTraceElement(StackTraceElement element) {
        String fileName = element.getFileName();
        String[] fileNameParts = fileName.split(PACKAGE_SEPARATOR, 2);
        String sourceFileName = fileNameParts[0];
        String packageName = fileNameParts.length > 1 ? fileNameParts[1] : "";
        return new SourceLocation(sourceFileName, packageName, element.getLineNumber());
    }

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
        ctx.cw.visitSource(sourceFileName + PACKAGE_SEPARATOR + packageName, null);
    }
}
