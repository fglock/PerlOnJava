package org.perlonjava.runtime;

public interface RuntimeScalarBehavior extends RuntimeScalarReference {
    int getInt();
    double getDouble();
    boolean getBoolean();

    void set(RuntimeScalar value);

    default RuntimeScalar not() {
        if (this.getBoolean()) {
            return new RuntimeScalar(0);
        }
        return new RuntimeScalar(1);
    }

}
