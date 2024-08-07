
/**
 * The RuntimeObject class is a base class for scalar, hash, and array variables.
 *
 */
public abstract class RuntimeObject implements ContextProvider {

  // Add the object to a list
  public void addToList(RuntimeList list) {
      list.add(this);
  }

}

