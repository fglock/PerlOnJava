# Perl Compiler Under Development

This is a Perl compiler under development. It compiles Perl into Java bytecode and runs it.

## Compile

```sh
javac -cp ./asm-9.7.jar:. *.java
```

## Run

```sh
java -cp ./asm-9.7.jar Main -e ' print 123 '
```

## Debugging Tools

Run emitting debug information

```sh
java -cp ./asm-9.7.jar Main --debug -e ' print 123 '
```

Compile only; can be combined with --debug

```sh
java -cp ./asm-9.7.jar Main -c -e ' print 123 '
```

```sh
java -cp ./asm-9.7.jar Main --debug -c -e ' print 123 '
```

Run the Lexer only

```sh
java -cp ./asm-9.7.jar Main --tokenize -e ' print 123 '
```

Run the Parser only

```sh
java -cp ./asm-9.7.jar Main --parse -e ' print 123 '
```

## Modules

### Lexer and Parser
- **Lexer**: Used to split the code into symbols like space, identifier, operator.
- **Parser**: Picks up the symbols and organizes them into an Abstract Syntax Tree (AST) of objects like block, subroutine.

### ClassWriter
- **ClassWriter**: Used to generate the bytecode for the class.
- The user code is translated into a method.
- The generated bytecode is loaded using a custom class loader.

### EmitterVisitor and EmitterContext
- **EmitterVisitor**: Used to generate the bytecode for the operations within the method.
- It traverses the AST and generates the corresponding ASM bytecode.
- **EmitterContext**: Holds the current state of the Symbol Table and calling context (void, scalar, etc).
- **PrinterVisitor**: Provides pretty-print stringification for the AST.

### AST Nodes: *Node
- Representations of AST nodes for code blocks, variable declarations, and operations.

### Symbol Table
- **SymbolTable** and **ScopedSymbolTable**: Manage variable names and their corresponding local variable indices.

### Runtime classes: Runtime*
- **Runtime**: Provides the implementation of the behavior of a Perl scalar variable, Code, Array, Hash.

### Main Method
- The main method generates the bytecode for the program body.
- The generated method is loaded into a variable as a code reference and executed.

