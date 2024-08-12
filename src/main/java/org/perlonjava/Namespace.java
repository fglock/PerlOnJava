import java.util.*;

/**
 * The Runtime class simulates Perl namespaces.
 *
 */
public class Namespace {

  private static Map<String, Runtime> globalVariables = new HashMap<>();
  private static Map<String, RuntimeArray> globalArrays = new HashMap<>();
  private static Map<String, RuntimeHash> globalHashes = new HashMap<>();

  // Static methods

  public static void initializeGlobals() {
      getGlobalVariable("$@");    // initialize $@ to "undef"
      getGlobalVariable("$_");    // initialize $_ to "undef"
      getGlobalVariable("$\"").set(" ");    // initialize $_ to " "
      getGlobalArray("@INC");
      getGlobalHash("%INC");
  }

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

  public static RuntimeArray getGlobalArray(String key) {
      RuntimeArray var = globalArrays.get(key);
      if (var == null) {
        var = new RuntimeArray();
        globalArrays.put(key, var);
      }
      return var;
  }

  public static boolean existsGlobalArray(String key) {
      return globalArrays.containsKey(key);
  }

  public static RuntimeHash getGlobalHash(String key) {
      RuntimeHash var = globalHashes.get(key);
      if (var == null) {
        var = new RuntimeHash();
        globalHashes.put(key, var);
      }
      return var;
  }

  public static boolean existsGlobalHash(String key) {
      return globalHashes.containsKey(key);
  }
}

