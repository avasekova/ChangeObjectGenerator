package me.deadcode.adka.entitychangegen;

public class ValueWrapper<T> {

    private T value;
    private boolean changed = false;

    public ValueWrapper() { }

    public ValueWrapper(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
        this.changed = true;
    }

    public boolean isChanged() {
        return changed;
    }
}
