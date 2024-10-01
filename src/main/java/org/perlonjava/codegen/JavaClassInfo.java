package org.perlonjava.codegen;

import org.objectweb.asm.Label;

public class JavaClassInfo {

    /**
     * The name of the Java class being generated.
     */
    public String javaClassName;
    /**
     * The label to which the current method should return.
     */
    public Label returnLabel;
    /**
     * ASM stack level
     */
    public int asmStackLevel;

    public JavaClassInfo() {
        this.javaClassName = EmitterMethodCreator.generateClassName();
        this.returnLabel = null;
        this.asmStackLevel = 0;
    }

    public String toString() {
        return "JavaClassInfo{\n" +
                "    javaClassName='" + javaClassName + "',\n" +
                "    returnLabel=" + (returnLabel != null ? returnLabel.toString() : "null") + ",\n" +
                "    asmStackLevel=" + asmStackLevel + "\n" +
                "}";
    }
}

