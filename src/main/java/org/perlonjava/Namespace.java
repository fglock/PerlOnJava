import java.util.*;

/**
 * The Runtime class simulates Perl namespaces.
 *
 */
public class Namespace {

  private static Map<String, Runtime> globalVariables = new HashMap<>();

  // Static methods
  public static Runtime setGlobalVariable(String key, Runtime value) {
      Runtime var = globalVariables.get(key);
      if (var == null) {
        var = new Runtime();
        globalVariables.put(key, var);
      }
      return var.set(value);
  }

  public static Runtime setGlobalVariable(String key, String value) {
      Runtime var = globalVariables.get(key);
      if (var == null) {
        var = new Runtime();
        globalVariables.put(key, var);
      }
      return var.set(value);
  }

  public static Runtime getGlobalVariable(String key) {
      Runtime var = globalVariables.get(key);
      if (var == null) {
        var = new Runtime();
        globalVariables.put(key, var);
      }
      return var;
  }

  public static boolean existsGlobalVariable(String key) {
      return globalVariables.containsKey(key);
  }
}

