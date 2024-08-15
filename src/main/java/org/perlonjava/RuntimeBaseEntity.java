package org.perlonjava;

/**
 * The RuntimeBaseEntity class is a base class for scalar, hash, and array variables.
 *
 */
public abstract class RuntimeBaseEntity implements RuntimeDataProvider {

  // Add the object to a list
  public void addToList(RuntimeList list) {
      list.add(this);
  }
}

