package org.perlonjava;

import java.util.*;

/**
 * The RuntimeScalar class simulates Perl namespaces.
 *
 */
public class Namespace {

  private static final Map<String, RuntimeScalar> globalVariables = new HashMap<>();
  private static final Map<String, RuntimeArray> globalArrays = new HashMap<>();
  private static final Map<String, RuntimeHash> globalHashes = new HashMap<>();

  // Static methods

  public static void initializeGlobals() {
      getGlobalVariable("$@");    // initialize $@ to "undef"
      getGlobalVariable("$_");    // initialize $_ to "undef"
      getGlobalVariable("$\"").set(" ");    // initialize $_ to " "
      getGlobalArray("@INC");
      getGlobalHash("%INC");
  }

  public static RuntimeScalar setGlobalVariable(String key, RuntimeScalar value) {
      RuntimeScalar var = globalVariables.get(key);
      if (var == null) {
        var = new RuntimeScalar();
        globalVariables.put(key, var);
      }
      return var.set(value);
  }

  public static RuntimeScalar setGlobalVariable(String key, String value) {
      RuntimeScalar var = globalVariables.get(key);
      if (var == null) {
        var = new RuntimeScalar();
        globalVariables.put(key, var);
      }
      return var.set(value);
  }

  public static RuntimeScalar getGlobalVariable(String key) {
      RuntimeScalar var = globalVariables.get(key);
      if (var == null) {
        var = new RuntimeScalar();
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

