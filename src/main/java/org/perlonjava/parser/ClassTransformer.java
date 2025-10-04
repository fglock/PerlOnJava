package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.runtime.*;
import java.util.ArrayList;
import java.util.List;

/**
 * ClassTransformer transforms Perl 5.38+ class syntax into standard Perl OO code.
 * 
 * This class is responsible for transforming the modern Perl class syntax
 * (introduced in Perl 5.38) into traditional Perl object-oriented code.
 * The transformation happens at parse time, converting:
 * - field declarations into instance variable initialization
 * - automatic constructor generation with named parameters
 * - reader method generation for fields with :reader attribute
 * - method declarations with implicit $self injection
 * - ADJUST blocks for post-construction initialization (TODO)
 * 
 * Example transformation:
 * <pre>
 * class Point {
 *     field $x :param :reader;
 *     field $y :param :reader = 0;
 *     method distance { ... }
 * }
 * </pre>
 * 
 * Becomes equivalent to:
 * <pre>
 * package Point;
 * sub new {
 *     my $class = shift;
 *     my %args = @_;
 *     my $self = bless {}, $class;
 *     $self->{x} = $args{x};
 *     $self->{y} = $args{y} // 0;
 *     return $self;
 * }
 * sub x { $_[0]->{x} }
 * sub y { $_[0]->{y} }
 * sub distance { my $self = shift; ... }
 * </pre>
 * 
 * @author PerlOnJava team
 * @since 2024
 */
public class ClassTransformer {
    
    /**
     * Transform a class block by processing fields, generating constructor and accessors.
     * This method performs the following transformations:
     * 1. Collects all field declarations and removes them from the block
     * 2. Pre-declares a constructor to make it visible to the parser
     * 3. Generates a constructor if one doesn't exist
     * 4. Generates reader methods for fields with :reader attribute
     * 5. Transforms regular methods to inject implicit $self
     * 
     * @param block The class block to transform
     * @param className The name of the class
     * @param parser The parser context for bytecode generation
     * @return The transformed block with generated methods
     */
    public static BlockNode transformClassBlock(BlockNode block, String className, Parser parser) {
        // Pre-declare the constructor immediately so it's visible to the parser
        // This is like doing "sub new;" in Perl
        predeclareConstructor(className);
        // Collect field nodes and other statements
        List<OperatorNode> fields = new ArrayList<>();
        List<Node> otherStatements = new ArrayList<>();
        List<SubroutineNode> methods = new ArrayList<>();
        
        // Get ADJUST blocks from parser (they were stored as anonymous subs by SpecialBlockParser)
        List<Node> adjustNodes = new ArrayList<>(parser.classAdjustBlocks);
        parser.classAdjustBlocks.clear(); // Clear for next class
        
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
            SubroutineNode constructor = generateConstructor(fields, className, adjustNodes);
            block.elements.add(constructor);
            // Register the constructor using the same logic as named subroutines
            // This handles all the bytecode generation automatically
            SubroutineParser.handleNamedSub(parser, constructor.name, constructor.prototype, 
                                           constructor.attributes, (BlockNode) constructor.block);
        }
        
        // Generate reader methods for fields with :reader attribute
        for (OperatorNode field : fields) {
            if (field.getAnnotation("attr:reader") != null) {
                SubroutineNode reader = generateReaderMethod(field);
                block.elements.add(reader);
                // Register the reader using the same logic as named subroutines
                SubroutineParser.handleNamedSub(parser, reader.name, reader.prototype,
                                               reader.attributes, (BlockNode) reader.block);
            }
        }
        
        // Transform methods to inject $self and add them back
        for (SubroutineNode method : methods) {
            transformMethod(method);
            block.elements.add(method);
            // Register the method in the runtime, just like constructor and reader methods
            SubroutineParser.handleNamedSub(parser, method.name, method.prototype,
                                           method.attributes, (BlockNode) method.block);
        }
        
        // Add all other statements back
        block.elements.addAll(otherStatements);
        
        return block;
    }
    
    /**
     * Generate a constructor (new) method from field declarations.
     * 
     * The generated constructor:
     * 1. Accepts the class name as first argument (for inheritance)
     * 2. Takes remaining arguments as named parameters (%args)
     * 3. Blesses an empty hashref into the class
     * 4. Initializes all fields from parameters or defaults
     * 5. Runs ADJUST blocks for post-construction initialization
     * 6. Returns the blessed object
     * 
     * @param fields List of field declarations with their attributes
     * @param className The name of the class
     * @param adjustBlocks List of ADJUST blocks to run after field initialization
     * @return A SubroutineNode representing the constructor
     */
    private static SubroutineNode generateConstructor(List<OperatorNode> fields, String className, List<Node> adjustNodes) {
        List<Node> bodyElements = new ArrayList<>();
        BlockNode body = new BlockNode(bodyElements, 0);
        
        // MINIMAL CONSTRUCTOR - Start with just bless {} and return
        // We'll add statements back one by one to identify the bytecode issue
        
        // Step 1: my $class = shift;  # Get class name from first argument
        ListNode myClassDecl = new ListNode(0);
        OperatorNode myClass = new OperatorNode("my", 
            new OperatorNode("$", new IdentifierNode("class", 0), 0), 0);
        myClassDecl.elements.add(myClass);
        OperatorNode shiftOp = new OperatorNode("shift", 
            new OperatorNode("@", new IdentifierNode("_", 0), 0), 0);
        BinaryOperatorNode classAssign = new BinaryOperatorNode("=", myClassDecl, shiftOp, 0);
        body.elements.add(classAssign);
        
        // Step 2: my %args = @_;  # Now @_ contains only the named parameters
        ListNode myArgsDecl = new ListNode(0);
        OperatorNode myArgs = new OperatorNode("my", 
            new OperatorNode("%", new IdentifierNode("args", 0), 0), 0);
        myArgsDecl.elements.add(myArgs);
        BinaryOperatorNode argsAssign = new BinaryOperatorNode("=", myArgsDecl,
            new OperatorNode("@", new IdentifierNode("_", 0), 0), 0);
        body.elements.add(argsAssign);
        
        // Step 3: my $self = bless {}, $class;
        ListNode mySelfDecl = new ListNode(0);
        OperatorNode mySelf = new OperatorNode("my", 
            new OperatorNode("$", new IdentifierNode("self", 0), 0), 0);
        mySelfDecl.elements.add(mySelf);
        
        // Create empty hash
        ListNode emptyList = new ListNode(0);
        HashLiteralNode emptyHash = new HashLiteralNode(emptyList.elements, 0);
        
        // Use $class variable instead of hardcoded class name
        OperatorNode classVar = new OperatorNode("$", new IdentifierNode("class", 0), 0);
        
        // bless {}, $class - as BinaryOperatorNode
        BinaryOperatorNode blessCall = new BinaryOperatorNode("bless", emptyHash, classVar, 0);
        
        // my $self = bless {}, $class;
        BinaryOperatorNode selfAssign = new BinaryOperatorNode("=", mySelfDecl, blessCall, 0);
        body.elements.add(selfAssign);
        
        // Step 3: Add field initialization
        for (OperatorNode field : fields) {
            Node fieldInit = generateFieldInitialization(field);
            if (fieldInit != null) {
                body.elements.add(fieldInit);
            }
        }
        
        // Step 4: Run ADJUST blocks after field initialization
        // ADJUST blocks are anonymous subs that need to be called with $self
        // They run in the order they appear in the class
        for (Node adjustNode : adjustNodes) {
            // Each ADJUST block is an anonymous sub that needs to be called with $self
            // Generate: $adjustSub->($self)
            
            // Create the argument list: ($self)
            ListNode args = new ListNode(0);
            args.elements.add(new OperatorNode("$", new IdentifierNode("self", 0), 0));
            
            // Create the call: $adjustSub->($self)
            BinaryOperatorNode adjustCall = new BinaryOperatorNode("->", adjustNode, args, 0);
            body.elements.add(adjustCall);
        }
        
        // Step 5: return $self;
        body.elements.add(new OperatorNode("return", 
            new OperatorNode("$", new IdentifierNode("self", 0), 0), 0));
        
        /* COMMENTED OUT FOR DEBUGGING - Add back one by one
        // my $class = $_[0];  
        // Use $_[0] instead of shift to avoid bytecode verification issues
        ListNode myClassDecl = new ListNode(0);
        OperatorNode myClass = new OperatorNode("my", 
            new OperatorNode("$", new IdentifierNode("class", 0), 0), 0);
        myClassDecl.elements.add(myClass);
        // Create $_[0] access: $ _ [ 0 ]
        OperatorNode underscore = new OperatorNode("$", new IdentifierNode("_", 0), 0);
        List<Node> zeroList = new ArrayList<>();
        zeroList.add(new NumberNode("0", 0));
        ArrayLiteralNode indexZero = new ArrayLiteralNode(zeroList, 0);
        BinaryOperatorNode arrayAccess = new BinaryOperatorNode("[", underscore, indexZero, 0);
        BinaryOperatorNode classAssign = new BinaryOperatorNode("=", myClassDecl, arrayAccess, 0);
        body.elements.add(classAssign);
        
        // my %args = @_[1..$#_];  
        // Get remaining arguments (skip first which is class name)
        ListNode myArgsDecl = new ListNode(0);
        OperatorNode myArgs = new OperatorNode("my", 
            new OperatorNode("%", new IdentifierNode("args", 0), 0), 0);
        myArgsDecl.elements.add(myArgs);
        // For simplicity, just use @_ for now - it will include class name but that's ok
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
        
        // Run ADJUST blocks after field initialization
        // ADJUST blocks run in the order they appear in the class
        // Each block runs with $self available in scope
        for (BlockNode adjustBlock : adjustBlocks) {
            // Add all statements from the ADJUST block to the constructor
            body.elements.addAll(adjustBlock.elements);
        }
        
        // return $self;
        body.elements.add(new OperatorNode("return", 
            new OperatorNode("$", new IdentifierNode("self", 0), 0), 0));
        */
        
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
        
        // Handle null default values - use undef if not specified
        if (hasDefault && defaultValue == null) {
            defaultValue = new OperatorNode("undef", null, 0);
        }
        
        // $self->{fieldname} = ...
        // Use the correct AST structure: $self -> HashLiteralNode([IdentifierNode])
        OperatorNode selfVar = new OperatorNode("$", new IdentifierNode("self", 0), 0);
        List<Node> keyList = new ArrayList<>();
        keyList.add(new IdentifierNode(name, 0));  // Use IdentifierNode, not StringNode!
        HashLiteralNode hashSubscript = new HashLiteralNode(keyList, 0);
        BinaryOperatorNode selfField = new BinaryOperatorNode("->", selfVar, hashSubscript, 0);
        
        Node value;
        if (hasParam) {
            // $args{fieldname} // default_or_undef
            // Use correct structure: %args becomes $args in hash access
            OperatorNode argsVar = new OperatorNode("$", new IdentifierNode("args", 0), 0);
            List<Node> argKeyList = new ArrayList<>();
            argKeyList.add(new IdentifierNode(name, 0));  // Use IdentifierNode, not StringNode!
            HashLiteralNode argHashSubscript = new HashLiteralNode(argKeyList, 0);
            BinaryOperatorNode argsAccess = new BinaryOperatorNode("{", argsVar, argHashSubscript, 0);
            
            if (hasDefault) {
                // Use // operator for explicit default value
                value = new BinaryOperatorNode("//", argsAccess, defaultValue, 0);
            } else if ("@".equals(sigil)) {
                // Array field without explicit default should default to []
                ListNode emptyList = new ListNode(0);
                Node emptyArray = new ArrayLiteralNode(emptyList.elements, 0);
                value = new BinaryOperatorNode("//", argsAccess, emptyArray, 0);
            } else if ("%".equals(sigil)) {
                // Hash field without explicit default should default to {}
                ListNode emptyList = new ListNode(0);
                Node emptyHash = new HashLiteralNode(emptyList.elements, 0);
                value = new BinaryOperatorNode("//", argsAccess, emptyHash, 0);
            } else {
                // Scalar fields can be undef if not provided
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
                value = new OperatorNode("undef", null, 0);
            }
        }
        
        return new BinaryOperatorNode("=", selfField, value, 0);
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
        // Correct structure: BinaryOperatorNode("[", $_, ArrayLiteralNode([0]))
        OperatorNode underscore = new OperatorNode("$", new IdentifierNode("_", 0), 0);
        List<Node> zeroList = new ArrayList<>();
        zeroList.add(new NumberNode("0", 0));
        ArrayLiteralNode indexZero = new ArrayLiteralNode(zeroList, 0);
        BinaryOperatorNode arg0 = new BinaryOperatorNode("[", underscore, indexZero, 0);
        
        // Now create the hash subscript
        List<Node> keyList = new ArrayList<>();
        keyList.add(new IdentifierNode(name, 0));  // Use IdentifierNode for hash keys
        HashLiteralNode hashSubscript = new HashLiteralNode(keyList, 0);
        BinaryOperatorNode fieldAccess = new BinaryOperatorNode("->", arg0, hashSubscript, 0);
        
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
        // shift @_ explicitly to avoid null operand
        OperatorNode shiftOp = new OperatorNode("shift",
            new OperatorNode("@", new IdentifierNode("_", 0), 0), 0);
        BinaryOperatorNode selfAssign = new BinaryOperatorNode("=", mySelfDecl, shiftOp, 0);
        
        methodBody.elements.addFirst(selfAssign);
        
        // If the method has a signature, add parameter declarations after $self = shift
        ListNode signatureAST = (ListNode) method.getAnnotation("signatureAST");
        if (signatureAST != null && !signatureAST.elements.isEmpty()) {
            // The signature AST contains parameter declarations like my ($w, $h) = @_;
            // Insert them after the $self assignment (at position 1)
            int insertPos = 1;
            for (Node sigElement : signatureAST.elements) {
                methodBody.elements.add(insertPos++, sigElement);
            }
        }
        
        // TODO: Transform field access within the method body
        // This would require walking the AST and converting $fieldname to $self->{fieldname}
        // For now, methods will need to use explicit $self->{fieldname} syntax
    }
    
    // Removed evaluateGeneratedSubroutine - no longer needed
    // We now use SubroutineParser.handleNamedSub directly which is much simpler
    // and ensures generated methods go through the exact same path as regular named subroutines
    
    /**
     * Pre-declare the constructor for a class.
     * This is equivalent to "sub new;" in Perl - it registers the subroutine
     * in the global namespace before it's actually defined, making it visible
     * to the parser when parsing method calls like Class->new().
     * 
     * @param className The name of the class
     */
    private static void predeclareConstructor(String className) {
        // Create the fully qualified constructor name
        String fullName = className + "::new";
        
        // Get or create the code reference in the global namespace
        // This registers the constructor even before it's generated
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(fullName);
        
        // Initialize as a code reference if needed
        if (codeRef.value == null) {
            codeRef.type = RuntimeScalarType.CODE;
            RuntimeCode code = new RuntimeCode("new", null);
            code.packageName = className;
            code.subName = "new";
            // No prototype for constructors - they accept any arguments
            code.prototype = null;
            codeRef.value = code;
        }
    }
}
