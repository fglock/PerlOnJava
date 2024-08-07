
/**
 * The AbstractRuntimeObject class is a base class for scalar, hash, and array variables.
 *
 */
public abstract class AbstractRuntimeObject implements ContextProvider {

  // Add the object to a list
  public void addToList(RuntimeList list) {
      list.add(this);
  }
}

