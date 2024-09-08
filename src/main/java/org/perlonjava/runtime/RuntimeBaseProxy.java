package org.perlonjava.runtime;

public abstract class RuntimeBaseProxy extends RuntimeScalar {
    RuntimeScalar lvalue;

    abstract void vivify();

    // Setters
    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        vivify();
        lvalue.set(value);
        this.type = lvalue.type;
        this.value = lvalue.value;
        return lvalue;
    }

    public RuntimeScalar undefine() {
        vivify();
        RuntimeScalar ret = lvalue.undefine();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    public RuntimeScalar chop() {
        vivify();
        RuntimeScalar ret = lvalue.chop();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    public RuntimeScalar chomp() {
        vivify();
        RuntimeScalar ret = lvalue.chomp();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    // Method to implement `$v->{key}`
    @Override
    public RuntimeScalar hashDerefGet(RuntimeScalar index) {
        vivify();
        RuntimeScalar ret = lvalue.hashDerefGet(index);
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    // Method to implement `$v->[key]`
    @Override
    public RuntimeScalar arrayDerefGet(RuntimeScalar index) {
        vivify();
        RuntimeScalar ret = lvalue.arrayDerefGet(index);
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    @Override
    public RuntimeScalar preAutoIncrement() {
        vivify();
        RuntimeScalar ret = lvalue.preAutoIncrement();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    public RuntimeScalar postAutoIncrement() {
        vivify();
        RuntimeScalar ret = lvalue.postAutoIncrement();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    @Override
    public RuntimeScalar preAutoDecrement() {
        vivify();
        RuntimeScalar ret = lvalue.preAutoDecrement();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    public RuntimeScalar postAutoDecrement() {
        vivify();
        RuntimeScalar ret = lvalue.postAutoDecrement();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }
}

