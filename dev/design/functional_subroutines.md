# Proposed Changes for PerlOnJava to use functions instead of methods

+ ## src/main/java/org/perlonjava/runtime/PerlSubroutine.java

+ package org.perlonjava.runtime;
+ 
+ import org.perlonjava.runtime.RuntimeArray;
+ import org.perlonjava.runtime.RuntimeList;
+ 
+ @FunctionalInterface
+ public interface PerlSubroutine {
+     // instance: The object representing the class where the subroutine was defined.
+     //           It can be a Symbol, Universal, or another class instance.
+     //           This object contains the closure variables added in its constructor.
+     // args: The arguments passed to the subroutine
+     // ctx: The context in which the subroutine is called (e.g., scalar, list)
+     RuntimeList apply(Object instance, RuntimeArray args, int ctx);
+ }

+ ## src/main/java/org/perlonjava/codegen/GeneratedSubroutine.java

+ package org.perlonjava.codegen;
+ 
+ import org.perlonjava.runtime.PerlSubroutine;
+ 
+ public class GeneratedSubroutine {
+     public final Class<?> generatedClass;
+     public final String[] parameterTypes;
+ 
+     public GeneratedSubroutine(Class<?> generatedClass, String[] parameterTypes) {
+         this.generatedClass = generatedClass;
+         this.parameterTypes = parameterTypes;
+     }
+ 
+     public PerlSubroutine createInstance(Object... args) throws Exception {
+         Class<?>[] parameterClasses = new Class<?>[parameterTypes.length];
+         for (int i = 0; i < parameterTypes.length; i++) {
+             parameterClasses[i] = Class.forName(parameterTypes[i]);
+         }
+         return (PerlSubroutine) generatedClass.getDeclaredConstructor(parameterClasses).newInstance(args);
+     }
+ }

+ ## src/main/java/org/perlonjava/codegen/EmitterMethodCreator.java

  public class EmitterMethodCreator implements Opcodes {
      // ... existing code ...
  
-     public static Class<?> createClassWithMethod(EmitterContext ctx, Node ast, boolean useTryCatch) {
+     public static GeneratedSubroutine createClassWithMethod(EmitterContext ctx, Node ast, boolean useTryCatch) {
          // ... existing code ...
  
          // Define the class with version, access flags, name, signature, superclass, and interfaces
-         cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, ctx.javaClassName, null, "java/lang/Object", null);
+         cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, ctx.javaClassName, null, "java/lang/Object", 
+                  new String[]{"org/perlonjava/runtime/PerlSubroutine"});
          ctx.logDebug("Create class: " + ctx.javaClassName);
  
          // ... existing field and constructor generation ...
  
          // Create the public "apply" method for the generated class
          ctx.logDebug("Create the method");
          ctx.mv = cw.visitMethod(
                  Opcodes.ACC_PUBLIC,
-                 "apply",
-                 "(Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
+                 "apply",
+                 "(Ljava/lang/Object;Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
                  null,
                  new String[]{"java/lang/Exception"});
          MethodVisitor mv = ctx.mv;
  
          // ... rest of the method generation ...
  
-         return loader.defineClass(javaClassNameDot, classData);
+         Class<?> generatedClass = loader.defineClass(javaClassNameDot, classData);
+ 
+         String[] parameterTypes = new String[env.length - skipVariables];
+         for (int i = skipVariables; i < env.length; i++) {
+             parameterTypes[i - skipVariables] = getVariableClassName(env[i]);
+         }
+ 
+         return new GeneratedSubroutine(generatedClass, parameterTypes);
      }
  }

+ ## src/main/java/org/perlonjava/perlmodule/Symbol.java

  public class Symbol {
      // ... existing code ...
  
+     public static void addSubroutine(String moduleName, String subName, String prototype, PerlSubroutine subroutine) {
+         try {
+             RuntimeScalar codeRef = new RuntimeScalar(new RuntimeCode(subroutine, prototype));
+             getGlobalCodeRef(moduleName + "::" + subName).set(codeRef);
+         } catch (Exception e) {
+             System.err.println("Warning: Failed to add subroutine " + subName + " to module " + moduleName + ": " + e.getMessage());
+         }
+     }
  
+     public static final PerlSubroutine qualify = (instance, args, ctx) -> {
+         if (args.size() < 1 || args.size() > 2) {
+             throw new IllegalStateException("Bad number of arguments for qualify()");
+         }
+         RuntimeScalar object = args.get(0);
+         RuntimeScalar packageName = null;
+         if (args.size() > 1) {
+             packageName = args.get(1);
+         } else {
+             // XXX TODO - default to caller()
+             packageName = new RuntimeScalar("main");
+         }
+         RuntimeScalar result;
+         if (object.type != RuntimeScalarType.STRING) {
+             result = object;
+         } else {
+             result = new RuntimeScalar(NameCache.normalizeVariableName(object.toString(), packageName.toString()));
+         }
+         RuntimeList list = new RuntimeList();
+         list.elements.add(result);
+         return list;
+     };
  
      public static void initialize() {
          // ... existing code ...
  
-         mm = clazz.getMethod("qualify_to_ref", RuntimeArray.class, int.class);
-         getGlobalCodeRef("Symbol::qualify_to_ref").set(new RuntimeScalar(
-                 new RuntimeCode(mm, instance, "$;$")));
+ 
+         addSubroutine("Symbol", "qualify", "$;$", qualify);
  
-         mm = clazz.getMethod("qualify", RuntimeArray.class, int.class);
-         getGlobalCodeRef("Symbol::qualify").set(new RuntimeScalar(
-                 new RuntimeCode(mm, instance, "$;$")));
+         addSubroutine("Symbol", "qualify_to_ref", "$;$", (instance, args, ctx) -> {
+             if (args.size() < 1 || args.size() > 2) {
+                 throw new IllegalStateException("Bad number of arguments for qualify_to_ref()");
+             }
+             RuntimeScalar object = qualify.apply(instance, args, ctx).scalar();
+             RuntimeScalar result;
+             if (object.type != RuntimeScalarType.STRING) {
+                 result = object;
+             } else {
+                 result = new RuntimeScalar().set(new RuntimeGlob(object.toString()));
+             }
+             RuntimeList list = new RuntimeList();
+             list.elements.add(result);
+             return list;
+         });
  
          // ... rest of initialize method ...
      }
  
      // ... rest of the class ...
  }


