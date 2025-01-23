
        // Initialize loop variables
        mv.visitInsn(Opcodes.ICONST_0); // int i = 0
        mv.visitVarInsn(Opcodes.ISTORE, 2);

        // Get the size of the input list
        mv.visitVarInsn(Opcodes.ALOAD, 0); // Load input list
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "RuntimeList", "size", "()I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 3); // Store size in local variable 3

        // Loop start label
        Label loopStart = new Label();
        mv.visitLabel(loopStart);

        // Check if i < size
        mv.visitVarInsn(Opcodes.ILOAD, 2); // Load i
        mv.visitVarInsn(Opcodes.ILOAD, 3); // Load size
        Label loopEnd = new Label();
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd); // if (i >= size) goto loopEnd

        // Get the element at index i
        mv.visitVarInsn(Opcodes.ALOAD, 0); // Load input list
        mv.visitVarInsn(Opcodes.ILOAD, 2); // Load i
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "RuntimeList", "get", "(I)Ljava/lang/Object;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4); // Store element in local variable 4

        // Evaluate the expression
        mv.visitVarInsn(Opcodes.ALOAD, 4); // Load element
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "ExampleClass", "evaluate", "(Ljava/lang/Object;)Z", false);
        Label skipAdd = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, skipAdd); // if (!evaluate(element)) goto skipAdd

        // Add the element to the output list
        mv.visitVarInsn(Opcodes.ALOAD, 1); // Load output list
        mv.visitVarInsn(Opcodes.ALOAD, 4); // Load element
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "RuntimeList", "add", "(Ljava/lang/Object;)Z", false);
        mv.visitInsn(Opcodes.POP); // Discard the boolean result of add

        // Skip add label
        mv.visitLabel(skipAdd);

        // Increment i
        mv.visitIincInsn(2, 1); // i++

        // Jump to loop start
        mv.visitJumpInsn(Opcodes.GOTO, loopStart);

        // Loop end label
        mv.visitLabel(loopEnd);

        // Return void
        mv.visitInsn(Opcodes.RETURN);

        mv.visitMaxs(2, 5);
        mv.visitEnd();

        cw.visitEnd();

