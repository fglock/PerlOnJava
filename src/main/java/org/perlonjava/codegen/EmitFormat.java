package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.FormatLine;
import org.perlonjava.astnode.FormatNode;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.runtime.RuntimeContextType;

/**
 * The EmitFormat class is responsible for handling format declarations
 * and generating the corresponding bytecode using ASM.
 */
public class EmitFormat {

    /**
     * Emits bytecode for a format declaration.
     * This creates a RuntimeFormat object and stores it in the global format table.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The format node representing the format declaration.
     */
    public static void emitFormat(EmitterVisitor emitterVisitor, FormatNode node) {
        EmitterContext ctx = emitterVisitor.ctx;
        MethodVisitor mv = ctx.mv;

        ctx.logDebug("FORMAT start: " + node.formatName);

        // Always generate format bytecode, even in VOID context
        // The side effect of storing the format is important

        // Create ArrayList for template lines first
        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);

        // Add each template line to the list
        for (int i = 0; i < node.templateLines.size(); i++) {
            mv.visitInsn(Opcodes.DUP); // Duplicate ArrayList reference

            // Create the appropriate FormatLine object based on type
            emitFormatLine(ctx, node.templateLines.get(i));

            // Add to ArrayList
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add",
                    "(Ljava/lang/Object;)Z", true);
            mv.visitInsn(Opcodes.POP); // Pop the boolean return value
        }

        // Now get the global format reference and set both template and compiled lines
        // Format name is already normalized by FormatParser using NameNormalizer
        // GlobalVariable.getGlobalFormatRef(formatName)
        mv.visitLdcInsn(node.formatName);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/GlobalVariable",
                "getGlobalFormatRef", "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeFormat;", false);

        // Duplicate the format reference for the second method call
        mv.visitInsn(Opcodes.DUP);

        // Set the template string first
        // Reconstruct template from format lines
        StringBuilder templateBuilder = new StringBuilder();
        for (FormatLine line : node.templateLines) {
            templateBuilder.append(line.content).append("\n");
        }
        String template = templateBuilder.toString();
        mv.visitLdcInsn(template);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeFormat",
                "setTemplate", "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeFormat;", false);
        mv.visitInsn(Opcodes.POP); // Pop the result

        // Now set the compiled lines
        // Swap so we have: [formatRef, compiledLines]
        mv.visitInsn(Opcodes.SWAP);

        // Call setCompiledLines on the global format reference
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeFormat",
                "setCompiledLines", "(Ljava/util/List;)Lorg/perlonjava/runtime/RuntimeFormat;", false);

        // Pop the result if in void context
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }

        ctx.logDebug("FORMAT end: " + node.formatName);
    }

    /**
     * Emits bytecode to create a FormatLine object.
     * This method handles the creation of different types of format lines
     * (PictureLine, ArgumentLine, CommentLine).
     *
     * @param ctx  The emitter context.
     * @param line The format line to emit.
     */
    private static void emitFormatLine(EmitterContext ctx, org.perlonjava.astnode.FormatLine line) {
        MethodVisitor mv = ctx.mv;

        if (line instanceof org.perlonjava.astnode.CommentLine commentLine) {
            // Create CommentLine(content, comment, tokenIndex)
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/astnode/CommentLine");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(commentLine.content);
            mv.visitLdcInsn(commentLine.comment);
            mv.visitLdcInsn(commentLine.tokenIndex);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/astnode/CommentLine",
                    "<init>", "(Ljava/lang/String;Ljava/lang/String;I)V", false);

        } else if (line instanceof org.perlonjava.astnode.PictureLine pictureLine) {
            // Create PictureLine(content, fields, literalText, tokenIndex)
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/astnode/PictureLine");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(pictureLine.content);

            // Create fields list
            emitFormatFieldsList(ctx, pictureLine.fields);

            mv.visitLdcInsn(pictureLine.literalText);
            mv.visitLdcInsn(pictureLine.tokenIndex);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/astnode/PictureLine",
                    "<init>", "(Ljava/lang/String;Ljava/util/List;Ljava/lang/String;I)V", false);

        } else if (line instanceof org.perlonjava.astnode.ArgumentLine argumentLine) {
            // Create ArgumentLine(content, expressions, tokenIndex)
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/astnode/ArgumentLine");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(argumentLine.content);

            // Create expressions list
            emitNodeList(ctx, argumentLine.expressions);

            mv.visitLdcInsn(argumentLine.tokenIndex);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/astnode/ArgumentLine",
                    "<init>", "(Ljava/lang/String;Ljava/util/List;I)V", false);
        }
    }

    /**
     * Emits bytecode to create a list of FormatField objects.
     *
     * @param ctx    The emitter context.
     * @param fields The list of format fields to emit.
     */
    private static void emitFormatFieldsList(EmitterContext ctx, java.util.List<org.perlonjava.astnode.FormatField> fields) {
        MethodVisitor mv = ctx.mv;

        // Create ArrayList
        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);

        // Add each field to the list
        for (org.perlonjava.astnode.FormatField field : fields) {
            mv.visitInsn(Opcodes.DUP); // Duplicate ArrayList reference
            emitFormatField(ctx, field);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add",
                    "(Ljava/lang/Object;)Z", true);
            mv.visitInsn(Opcodes.POP); // Pop boolean return value
        }
    }

    /**
     * Emits bytecode to create a FormatField object.
     *
     * @param ctx   The emitter context.
     * @param field The format field to emit.
     */
    private static void emitFormatField(EmitterContext ctx, org.perlonjava.astnode.FormatField field) {
        MethodVisitor mv = ctx.mv;

        if (field instanceof org.perlonjava.astnode.TextFormatField textField) {
            // Create TextFormatField(width, startPosition, isSpecialField, justification)
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/astnode/TextFormatField");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(textField.width);
            mv.visitLdcInsn(textField.startPosition);
            mv.visitLdcInsn(textField.isSpecialField);

            // Get justification enum value
            String justificationName = textField.justification.name();
            mv.visitFieldInsn(Opcodes.GETSTATIC, "org/perlonjava/astnode/TextFormatField$Justification",
                    justificationName, "Lorg/perlonjava/astnode/TextFormatField$Justification;");

            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/astnode/TextFormatField",
                    "<init>", "(IIZLorg/perlonjava/astnode/TextFormatField$Justification;)V", false);

        } else if (field instanceof org.perlonjava.astnode.NumericFormatField numericField) {
            // Create NumericFormatField(width, startPosition, isSpecialField, integerDigits, decimalPlaces)
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/astnode/NumericFormatField");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(numericField.width);
            mv.visitLdcInsn(numericField.startPosition);
            mv.visitLdcInsn(numericField.isSpecialField);
            mv.visitLdcInsn(numericField.integerDigits);
            mv.visitLdcInsn(numericField.decimalPlaces);

            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/astnode/NumericFormatField",
                    "<init>", "(IIZII)V", false);

        } else if (field instanceof org.perlonjava.astnode.MultilineFormatField multilineField) {
            // Create MultilineFormatField(width, startPosition, isSpecialField, multilineType)
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/astnode/MultilineFormatField");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(multilineField.width);
            mv.visitLdcInsn(multilineField.startPosition);
            mv.visitLdcInsn(multilineField.isSpecialField);

            // Get multiline type enum value
            String typeName = multilineField.multilineType.name();
            mv.visitFieldInsn(Opcodes.GETSTATIC, "org/perlonjava/astnode/MultilineFormatField$MultilineType",
                    typeName, "Lorg/perlonjava/astnode/MultilineFormatField$MultilineType;");

            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/astnode/MultilineFormatField",
                    "<init>", "(IIZLorg/perlonjava/astnode/MultilineFormatField$MultilineType;)V", false);
        }
    }

    /**
     * Emits bytecode to create a list of Node objects.
     *
     * @param ctx   The emitter context.
     * @param nodes The list of nodes to emit.
     */
    private static void emitNodeList(EmitterContext ctx, java.util.List<org.perlonjava.astnode.Node> nodes) {
        MethodVisitor mv = ctx.mv;

        // Create ArrayList
        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);

        // Add each node to the list
        for (org.perlonjava.astnode.Node node : nodes) {
            mv.visitInsn(Opcodes.DUP); // Duplicate ArrayList reference

            // For now, create simple StringNode objects
            // In a full implementation, we would emit the actual node bytecode
            if (node instanceof org.perlonjava.astnode.StringNode stringNode) {
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/astnode/StringNode");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(stringNode.value);
                mv.visitLdcInsn(stringNode.tokenIndex);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/astnode/StringNode",
                        "<init>", "(Ljava/lang/String;I)V", false);
            } else {
                // For other node types, create a placeholder StringNode
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/astnode/StringNode");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(node.toString());
                mv.visitLdcInsn(0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/astnode/StringNode",
                        "<init>", "(Ljava/lang/String;I)V", false);
            }

            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add",
                    "(Ljava/lang/Object;)Z", true);
            mv.visitInsn(Opcodes.POP); // Pop boolean return value
        }
    }
}
