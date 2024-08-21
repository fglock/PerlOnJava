# TODO

## Easy Wins
- all done

## Harder to Implement
- `BEGIN` block
- Test suite

## Easy, but Low Impact
- `wantarray()`
- `warn()`
- `die()`
- Other builtins

## More Difficult, and Low Impact
- `caller()`
- `goto()`
- Thread
- Optimizations
- implement subroutine using JVM Function instead of Method

## After the basics are implemented - next set of basic features:
- Blessed objects

## Cleanup
- Cleanup the closure code to only add the lexical variables mentioned in the AST
- Refactor anonymous subroutines to use Function instead of Method

## Runtime Format Error Messages and Warnings
- catch and reformat errors like division by zero

## Test Different Perl Data Types
- Experiment with `Perlito` runtime

## Global Variables and Namespaces
- Named subroutine declaration
- Perl classes

## Local Variables
- Set up restoring the `local` value before `RETURN`

## Implement Thread-Safety
- It may need locking when calling ASM

## Create Multiple Classes
- Ensure GC works for these classes

## `goto`, labels

## `eval` String
- Optimize `ctx.symbolTable` at `eval` string if needed

## `BEGIN` Block

## Main.java Read Code from STDIN
```java
// Read input from STDIN
Scanner scanner = new Scanner(System.in);
System.out.println("Enter code:");
String code = scanner.nextLine();
scanner.close();
```

## Implement __SUB__

```
// Step 1: Load 'this' onto the stack.
mv.visitVarInsn(Opcodes.ALOAD, 0); // 'this' is always at index 0 in an instance method.

// Step 2: Load the first argument 'a' (RuntimeArray) onto the stack.
mv.visitVarInsn(Opcodes.ALOAD, indexOfA); // Replace indexOfA with the actual index of 'a'.

// Step 3: Load the second argument 'callContext' (RuntimeContextType) onto the stack.
mv.visitVarInsn(Opcodes.ALOAD, indexOfCallContext); // Replace indexOfCallContext with the actual index of 'callContext'.

// Step 4: Invoke the 'apply' method.
mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                   "org/perlonjava/RuntimeScalar",
                   "apply", 
                   "(Lorg/perlonjava/RuntimeArray;Lorg/perlonjava/RuntimeContextType;)Lorg/perlonjava/RuntimeList;", 
                   false);
```
