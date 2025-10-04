package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.runtime.*;
import java.util.ArrayList;
import java.util.List;

/**
 * ClassTransformer transforms Perl class syntax into standard Perl OO code.
 * It collects fields and generates constructors, accessors, and transforms methods.
 */
public class ClassTransformer {
    
    /**
     * Transform a class block by processing fields, generating constructor and accessors.
     * 
     * @param block The class block to transform
     * @param className The name of the class
     * @return The transformed block
     */
    public static BlockNode transformClassBlock(BlockNode block, String className) {
        // Collect field nodes and other statements
        List<OperatorNode> fields = new ArrayList<>();
        List<Node> otherStatements = new ArrayList<>();
        List<SubroutineNode> methods = new ArrayList<>();
        SubroutineNode existingConstructor = null;
        
        // Scan the block for fields, methods, and existing constructor
        for (Node element : block.elements) {
            if (element instanceof OperatorNode opNode && "field".equals(opNode.operator)) {
                fields.add(opNode);
                // Don't add fields to otherStatements - they're transformed
            } else if (element instanceof SubroutineNode subNode) {
                if ("new".equals(subNode.name)) {
                    existingConstructor = subNode;
                    otherStatements.add(element); // Keep existing constructor
                } else if (subNode.getBooleanAnnotation("isMethod")) {
                    methods.add(subNode);
                    // Don't add methods to otherStatements - they'll be transformed
                } else {
                    otherStatements.add(element); // Regular subroutines
                }
            } else {
                otherStatements.add(element);
            }
        }
        
        // Clear the block and rebuild it
        block.elements.clear();
        
        // Add the package declaration back (it's always first)
        if (!otherStatements.isEmpty() && otherStatements.get(0) instanceof OperatorNode opNode 
            && ("class".equals(opNode.operator) || "package".equals(opNode.operator))) {
            block.elements.add(otherStatements.remove(0));
        }
        
        // Generate constructor if not present
        if (existingConstructor == null && !fields.isEmpty()) {
            SubroutineNode constructor = generateConstructor(fields, className);
            block.elements.add(constructor);
            // Evaluate the constructor to register it in runtime
            evaluateGeneratedSubroutine(constructor, className);
        }
        
        // Generate reader methods for fields with :reader attribute
        for (OperatorNode field : fields) {
            if (field.getAnnotation("attr:reader") != null) {
                SubroutineNode reader = generateReaderMethod(field);
                block.elements.add(reader);
                // Evaluate the reader to register it in runtime
                evaluateGeneratedSubroutine(reader, className);
            }
        }
        
        // Transform methods to inject $self and add them back
        for (SubroutineNode method : methods) {
            transformMethod(method);
            block.elements.add(method);
        }
        
        // Add all other statements back
        block.elements.addAll(otherStatements);
        
        return block;
    }
    
    /**
     * Generate a constructor (new) method from field declarations.
     */
    private static SubroutineNode generateConstructor(List<OperatorNode> fields, String className) {
        List<Node> bodyElements = new ArrayList<>();
        BlockNode body = new BlockNode(bodyElements, 0);
        
        // my $class = shift;
        ListNode myClassDecl = new ListNode(0);
        OperatorNode myClass = new OperatorNode("my", 
            new OperatorNode("$", new IdentifierNode("class", 0), 0), 0);
        myClassDecl.elements.add(myClass);
        BinaryOperatorNode classAssign = new BinaryOperatorNode("=", myClassDecl,
            new OperatorNode("shift", null, 0), 0);
        body.elements.add(classAssign);
        
        // my %args = @_;
        ListNode myArgsDecl = new ListNode(0);
        OperatorNode myArgs = new OperatorNode("my", 
            new OperatorNode("%", new IdentifierNode("args", 0), 0), 0);
        myArgsDecl.elements.add(myArgs);
        BinaryOperatorNode argsAssign = new BinaryOperatorNode("=", myArgsDecl,
            new OperatorNode("@", new IdentifierNode("_", 0), 0), 0);
        body.elements.add(argsAssign);
        
        // my $self = bless {}, $class;
        ListNode mySelfDecl = new ListNode(0);
        OperatorNode mySelf = new OperatorNode("my", 
            new OperatorNode("$", new IdentifierNode("self", 0), 0), 0);
        mySelfDecl.elements.add(mySelf);
        
        ListNode blessArgs = new ListNode(0);
        ListNode emptyList = new ListNode(0);
        blessArgs.elements.add(new HashLiteralNode(emptyList.elements, 0)); // empty hash {}
        blessArgs.elements.add(new OperatorNode("$", new IdentifierNode("class", 0), 0));
        OperatorNode blessCall = new OperatorNode("bless", blessArgs, 0);
        
        BinaryOperatorNode selfAssign = new BinaryOperatorNode("=", mySelfDecl, blessCall, 0);
        body.elements.add(selfAssign);
        
        // Initialize fields
        for (OperatorNode field : fields) {
            Node fieldInit = generateFieldInitialization(field);
            if (fieldInit != null) {
                body.elements.add(fieldInit);
            }
        }
        
        // return $self;
        body.elements.add(new OperatorNode("return", 
            new OperatorNode("$", new IdentifierNode("self", 0), 0), 0));
        
        // Create the subroutine node
        SubroutineNode constructor = new SubroutineNode(
            "new",      // name
            null,       // prototype
            null,       // attributes
            body,       // body
            false,      // isAnonymous
            0           // tokenIndex
        );
        
        return constructor;
    }
    
    /**
     * Generate field initialization code for the constructor.
     */
    private static Node generateFieldInitialization(OperatorNode field) {
        String sigil = (String) field.getAnnotation("sigil");
        String name = (String) field.getAnnotation("name");
        boolean hasParam = field.getAnnotation("attr:param") != null;
        boolean hasDefault = field.getBooleanAnnotation("hasDefault");
        Node defaultValue = field.operand; // The default value if hasDefault is true
        
        // $self->{fieldname} = ...
        OperatorNode selfVar = new OperatorNode("$", new IdentifierNode("self", 0), 0);
        ListNode hashKey = new ListNode(0);
        hashKey.elements.add(new StringNode(name, 0));
        BinaryOperatorNode arrow = new BinaryOperatorNode("->", selfVar, 
            new HashLiteralNode(hashKey.elements, 0), 0);
        
        Node value;
        if (hasParam) {
            // $args{fieldname} // default_or_undef
            OperatorNode argsHash = new OperatorNode("%", new IdentifierNode("args", 0), 0);
            BinaryOperatorNode argsAccess = new BinaryOperatorNode("{", argsHash,
                new StringNode(name, 0), 0);
            
            if (hasDefault) {
                // Use // operator for default value
                value = new BinaryOperatorNode("//", argsAccess, defaultValue, 0);
            } else {
                value = argsAccess;
            }
        } else if (hasDefault) {
            value = defaultValue;
        } else {
            // Initialize to appropriate empty value based on sigil
            if ("@".equals(sigil)) {
                ListNode emptyList = new ListNode(0);
                value = new ArrayLiteralNode(emptyList.elements, 0); // []
            } else if ("%".equals(sigil)) {
                ListNode emptyList = new ListNode(0);
                value = new HashLiteralNode(emptyList.elements, 0); // {}
            } else {
                value = new IdentifierNode("undef", 0);
            }
        }
        
        return new BinaryOperatorNode("=", arrow, value, 0);
    }
    
    /**
     * Generate a reader method for a field.
     */
    private static SubroutineNode generateReaderMethod(OperatorNode field) {
        String name = (String) field.getAnnotation("name");
        String readerName = (String) field.getAnnotation("attr:reader");
        if (readerName == null || readerName.isEmpty()) {
            readerName = name; // Use field name as method name
        }
        
        // Create method body: return $_[0]->{fieldname}
        List<Node> bodyElements = new ArrayList<>();
        BlockNode body = new BlockNode(bodyElements, 0);
        
        // $_[0]->{fieldname}
        OperatorNode arg0 = new OperatorNode("$", 
            new BinaryOperatorNode("[", 
                new OperatorNode("@", new IdentifierNode("_", 0), 0),
                new NumberNode("0", 0), 0), 0);
        ListNode hashKey = new ListNode(0);
        hashKey.elements.add(new StringNode(name, 0));
        BinaryOperatorNode fieldAccess = new BinaryOperatorNode("->", arg0,
            new HashLiteralNode(hashKey.elements, 0), 0);
        
        body.elements.add(fieldAccess);
        
        // Create the subroutine node
        SubroutineNode reader = new SubroutineNode(
            readerName, // name
            null,       // prototype
            null,       // attributes
            body,       // body
            false,      // isAnonymous
            0           // tokenIndex
        );
        
        return reader;
    }
    
    /**
     * Transform a method to inject implicit $self.
     * This modifies the method in place.
     */
    private static void transformMethod(SubroutineNode method) {
        if (method.block == null || !(method.block instanceof BlockNode)) {
            return;
        }
        
        BlockNode methodBody = (BlockNode) method.block;
        if (methodBody.elements.isEmpty()) {
            return;
        }
        
        // Insert "my $self = shift;" at the beginning of the method
        ListNode mySelfDecl = new ListNode(0);
        OperatorNode mySelf = new OperatorNode("my", 
            new OperatorNode("$", new IdentifierNode("self", 0), 0), 0);
        mySelfDecl.elements.add(mySelf);
        BinaryOperatorNode selfAssign = new BinaryOperatorNode("=", mySelfDecl,
            new OperatorNode("shift", null, 0), 0);
        
        methodBody.elements.addFirst(selfAssign);
        
        // TODO: Transform field access within the method body
        // This would require walking the AST and converting $fieldname to $self->{fieldname}
        // For now, methods will need to use explicit $self->{fieldname} syntax
    }
    
    /**
     * Evaluate a generated subroutine to register it in the runtime.
     * This follows the same pattern as handleNamedSub in SubroutineParser.
     */
    private static void evaluateGeneratedSubroutine(SubroutineNode subNode, String packageName) {
        if (subNode.name == null) {
            return; // Anonymous subroutines not supported here
        }
        
        // Create the fully qualified name
        String fullName = packageName + "::" + subNode.name;
        
        // Get or create the code reference in the global namespace
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(fullName);
        
        // Initialize as a code reference if needed
        if (codeRef.value == null) {
            codeRef.type = RuntimeScalarType.CODE;
            codeRef.value = new RuntimeCode(subNode.name, subNode.attributes);
        }
        
        // Set up the RuntimeCode object
        RuntimeCode code = (RuntimeCode) codeRef.value;
        code.prototype = subNode.prototype;
        code.attributes = subNode.attributes;
        code.subName = subNode.name;
        code.packageName = packageName;
        
        // For now, we mark the code as having a constant value
        // This is a simplified approach - ideally we'd store the AST
        // and compile it when first called, like handleNamedSub does
        // TODO: Implement proper lazy compilation with compilerSupplier
        code.constantValue = new RuntimeList();
        
        // The actual implementation will need to be compiled when called
        // For now this at least registers the method in the runtime
    }
}
