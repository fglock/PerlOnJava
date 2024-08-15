package org.perlonjava;

/**
 * The AbstractRuntimeObject class is a base class for scalar, hash, and array variables.
 *
 */
public abstract class AbstractRuntimeObject implements RuntimeDataProvider {

  // Add the object to a list
  public void addToList(RuntimeList list) {
      list.add(this);
  }
}

