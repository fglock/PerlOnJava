# Implementation Plan: Perl Class Features via AST Transformation

## Overview
This document outlines the implementation strategy for Perl's new class syntax features (introduced in Perl 5.38+) in PerlOnJava, focusing on AST transformation approach for simplicity.

## REVISED APPROACH (Updated 2025-10-04)
After initial implementation, we simplified the approach:
- **No FieldNode class needed** - Use AST annotations on existing OperatorNode
- **Transform at parse time** - Convert class syntax to standard Perl during parsing
- **Reuse existing nodes** - Generate standard SubroutineNode for constructors/accessors
- **Test with --parse** - Verify transformations before bytecode generation

## IMPLEMENTATION STATUS (2025-10-04 17:00)

### ✅ Successfully Implemented:
1. **Field declarations** - `field $x :param :reader = default_value`
   - Parsing with sigil support ($, @, %)
   - Attributes: `:param`, `:reader`
   - Default values
   - Stored as annotations on OperatorNode

2. **Constructor generation** - Automatic `new` method
   - Accepts named parameters for `:param` fields
   - Initializes all fields with defaults or undef
   - Runs ADJUST blocks after field initialization
   - Returns blessed object

3. **Reader methods** - Automatic accessors for `:reader` fields
   - Generated as simple getter methods
   - Return field value from object hash

4. **Method declarations** - `method name { ... }`
   - Parsed directly with simplified approach
   - Implicit `$self = shift` injected at start
   - Full method body preserved

5. **ADJUST blocks** - Post-construction initialization
   - Collected during class parsing
   - Appended to constructor after field initialization
   - Run in order of appearance with $self available

6. **Class transformation** - Complete AST transformation
   - Fields collected and removed from class body
   - Constructor and readers generated
   - Methods transformed with $self injection
   - ADJUST blocks integrated into constructor
   - All done during parsing phase

### ⚠️ Known Limitations:
1. **Runtime constructor calls** - Parser doesn't see generated constructors
   - Issue: `Class->new(...)` fails at parse time  
   - Root cause: Constructor exists only in AST, not in runtime GlobalVariable.globalCodeRefs
   - The generated SubroutineNode cannot be registered as RuntimeCode until bytecode generation
   - Workaround: Use explicit constructor definition or factory methods for now

2. **Method signatures** - Currently skipped
   - Methods with parameters work but signatures not fully processed
   - Would need SignatureParser integration

3. **ADJUST blocks** - Not implemented yet
   - Would need special handling in class transformation

4. **Field access in methods** - Manual $self->{field} required
   - No automatic field variable transformation
   - Methods must use explicit hash access

## Implementation Files

### Core Implementation:
- `src/main/java/org/perlonjava/parser/FieldParser.java` - Parses field declarations
- `src/main/java/org/perlonjava/parser/ClassTransformer.java` - Transforms classes to OO
- `src/main/java/org/perlonjava/parser/StatementResolver.java` - Added field/method parsing
- `src/main/java/org/perlonjava/parser/StatementParser.java` - Hooks class transformation

### Test Files:
- `src/test/resources/class_features.t` - Test suite (skipped until runtime fix)
- `demo_class_features.pl` - Demonstration of working features

## Next Steps

### Priority 1: Fix Runtime Constructor Recognition
The generated `new()` method isn't visible to the parser at parse time, causing runtime failures.
Possible solutions:
1. Register generated methods in symbol table during transformation
2. Implement runtime method resolution for blessed references
3. Special-case `->new()` calls for classes

### Priority 2: Add ADJUST Blocks
```perl
class Foo {
    field $x;
    ADJUST {
        $x = calculate_initial_value();
    }
}
```
Implementation: Collect ADJUST blocks during parsing, append to constructor after field init.

### Priority 3: Integrate SignatureParser for Methods
Currently method signatures are skipped. Should integrate with existing SignatureParser.

### Priority 4: Field Variable Transformation
Transform bare field variables to `$self->{field}` within method bodies:
```perl
method foo {
    $x = 10;  # Should become $self->{x} = 10
}
```

## Testing Strategy
1. Parse-level testing with `--parse` flag works perfectly
2. Runtime testing blocked by constructor recognition issue
3. Full test suite in `src/test/resources/class_features.t` ready to enable

## Commit Summary
```
Implement Perl 5.38+ class features via AST transformation

- Add field declarations with :param and :reader attributes
- Generate constructors automatically with named parameters
- Generate reader methods for fields marked with :reader
- Parse methods with implicit $self injection
- Transform entire class block at parse time to standard Perl OO

Known limitation: Runtime constructor calls fail due to parser visibility.
Next step: Fix runtime method resolution for generated constructors.
```

## Feature Pragma Requirement
The class syntax is only available when explicitly enabled:
```perl
use feature 'class';
# or
use v5.38;  # or later versions that include the feature
```

**Implementation:**
- Parser must check if 'class' feature is enabled before accepting class syntax
- Track feature state in ParserContext
- Throw syntax error if class/field/method used without feature enabled
- Also handle `no warnings 'experimental::class'`

## Core Strategy: AST Transformation
Instead of implementing these features in the runtime, we'll transform the new class syntax into traditional Perl OO code during parsing. This approach:
- Minimizes runtime changes
- Leverages existing OO infrastructure
- Maintains compatibility
- Simplifies implementation

## Features to Implement

### 1. `:isa` - Class Inheritance Attribute
**Perl Syntax:**
```perl
class Child :isa(Parent) {
    # ...
}
```

**AST Transformation:**
```perl
package Child;
use base 'Parent';  # or @ISA = ('Parent');
# ...
```

**Implementation Steps:**
1. Parse `:isa(ClassName)` attribute in ClassNode
2. Transform to `@ISA` assignment or `use base` statement
3. Insert at beginning of class block

### 2. `field` - Field Declaration
**Perl Syntax:**
```perl
class Point {
    field $x;
    field $y = 0;
    field @coords;
    field %options = (debug => 1);
}
```

**AST Transformation:**
```perl
package Point;
# Note: Objects are blessed OBJECT refs, not HASH refs
sub new {
    my $class = shift;
    my $self = bless Object::Pad::MOP::Instance->new, $class;
    # Scalar fields
    $self->set_field('x', undef);
    $self->set_field('y', 0);
    # Array fields
    $self->set_field('coords', []);
    # Hash fields  
    $self->set_field('options', {debug => 1});
    return $self;
}
```

**Important:** Objects must have reftype 'OBJECT', not 'HASH'

**Implementation Steps:**
1. Collect all `field` declarations during parsing
2. Generate constructor if not present
3. Initialize fields in constructor
4. Store field metadata for validation

### 3. `:param` - Constructor Parameter Attribute
**Perl Syntax:**
```perl
class Point {
    field $x :param;
    field $y :param = 0;
    field @coords :param = ();
}
```

**AST Transformation:**
```perl
package Point;
sub new {
    my $class = shift;
    my %args = @_;
    
    # Validate unrecognized parameters
    my %valid_params = map { $_ => 1 } qw(x y coords);
    for my $key (keys %args) {
        die qq{Unrecognised parameters for "$class" constructor: $key}
            unless $valid_params{$key};
    }
    
    my $self = bless Object::Pad::MOP::Instance->new, $class;
    $self->set_field('x', exists $args{x} ? $args{x} : die "Missing required parameter: x");
    $self->set_field('y', exists $args{y} ? $args{y} : 0);
    $self->set_field('coords', exists $args{coords} ? $args{coords} : []);
    return $self;
}
```

**Implementation Steps:**
1. Parse `:param` attribute on fields
2. Generate parameter handling in constructor
3. Add validation for required parameters
4. Handle default values

### 4. `:reader` - Reader Method Attribute
**Perl Syntax:**
```perl
class Point {
    field $x :reader;
    field $y :reader(get_y);
    field @coords :reader;
    field %options :reader();  # Empty parens = use default name
}
```

**AST Transformation:**
```perl
package Point;
# ... field initialization ...
sub x { $_[0]->get_field('x') }
sub get_y { $_[0]->get_field('y') }
sub coords { 
    my $self = shift;
    my $array_ref = $self->get_field('coords');
    wantarray ? @$array_ref : scalar @$array_ref;
}
sub options {
    my $self = shift;
    my $hash_ref = $self->get_field('options');
    wantarray ? %$hash_ref : scalar keys %$hash_ref;
}
```

**Implementation Steps:**
1. Parse `:reader` or `:reader(name)` attribute
2. Generate getter methods
3. Use field name as method name if not specified
4. Insert methods into class AST

### 5. `method` - Method Declaration
**Perl Syntax:**
```perl
class Point {
    method move($dx, $dy) {
        $x += $dx;
        $y += $dy;
    }
}
```

**AST Transformation:**
```perl
package Point;
sub move {
    my $self = shift;
    my ($dx, $dy) = @_;
    $self->{x} += $dx;
    $self->{y} += $dy;
}
```

**Implementation Steps:**
1. Parse `method` keyword like `sub`
2. Inject `$self` as first parameter
3. Transform field access: `$x` → `$self->{x}`
4. Generate standard subroutine

### 6. `ADJUST` - Object Initialization Block
**Perl Syntax:**
```perl
class Point {
    field $x :param;
    field $y :param;
    
    ADJUST {
        die "x must be positive" if $x < 0;
    }
}
```

**AST Transformation:**
```perl
package Point;
sub new {
    my $class = shift;
    my %args = @_;
    my $self = bless {}, $class;
    $self->{x} = $args{x};
    $self->{y} = $args{y};
    
    # ADJUST block
    do {
        my $x = $self->{x};
        my $y = $self->{y};
        die "x must be positive" if $x < 0;
    };
    
    return $self;
}
```

**Implementation Steps:**
1. Collect ADJUST blocks during parsing
2. Insert after field initialization in constructor
3. Set up lexical aliases for fields
4. Execute in order of declaration

## Implementation Order

### Phase 1: Basic Structure (Week 1)
1. Extend parser to recognize `class` with `:isa`
2. Transform `:isa` to `@ISA` assignment
3. Basic `field` declaration (without attributes)
4. Auto-generate simple constructor

### Phase 2: Field Attributes (Week 2)
1. Implement `:param` attribute
2. Implement `:reader` attribute
3. Handle default values
4. Add parameter validation

### Phase 3: Methods and ADJUST (Week 3)
1. Implement `method` keyword
2. Transform field access in methods
3. Implement `ADJUST` blocks
4. Ensure proper execution order

### Phase 4: Edge Cases (Week 4)
1. Multiple inheritance with `:isa`
2. Field name conflicts
3. Method overriding
4. Error messages matching Perl's

## Key Implementation Files

### Parser Changes
- `ClassParser.java` - New file for class syntax
- `FieldParser.java` - New file for field declarations
- `MethodParser.java` - Extend existing method parsing
- `Parser.java` - Add entry points for new keywords

### AST Nodes
- `ClassNode.java` - Represents class declaration
- `FieldNode.java` - Represents field declaration
- `MethodNode.java` - Enhanced for method vs sub
- `AdjustNode.java` - Represents ADJUST block

### AST Transformation
- `ClassTransformer.java` - Main transformation logic
- `ConstructorGenerator.java` - Generate new() method
- `FieldAccessRewriter.java` - Transform field access

### Emitter Updates
- `EmitClass.java` - Emit transformed class code
- `EmitVariable.java` - Handle field access

## Additional Features from Test Review

### Unit Classes
```perl
# Class without body
class Foo::Bar;
method m { return "unit class method" }

# Equivalent to:
package Foo::Bar;
sub new { bless Object::Pad::MOP::Instance->new, shift }
sub m { return "unit class method" }
```

### Array and Hash Fields
- Fields can be arrays (`field @items`) and hashes (`field %options`)
- Reader methods for array/hash fields respect context (list vs scalar)
- Array fields in scalar context return count
- Hash fields in scalar context return key count

### Object Internal Representation
- Objects must have `reftype` of 'OBJECT', not 'HASH'
- This is critical for compatibility with builtin::reftype
- May need custom object representation or tie magic

### Constructor Validation
- Unrecognized parameters must throw specific error
- Error format: `Unrecognised parameters for "ClassName" constructor: param_name`
- All parameters must be validated before object creation

## Testing Strategy

### Core Test Files from t/class/
1. **class.t** - Basic class syntax, __CLASS__ token, unit classes
2. **field.t** - Field declarations, initialization, distinctness
3. **accessor.t** - :reader attribute for all field types
4. **construct.t** - :param attribute, constructor validation
5. **inherit.t** - :isa attribute, inheritance
6. **method.t** - Method syntax, field access within methods
7. **phasers.t** - BEGIN, ADJUST blocks
8. **destruct.t** - DESTROY, object cleanup

### Unit Tests
```perl
# Feature pragma required
use feature 'class';
no warnings 'experimental::class';

# Basic class with all field types
class Point {
    field $x :param :reader;
    field $y :param :reader = 0;
    field @history :reader;
    field %metadata;
    
    ADJUST {
        push @history, [$x, $y];
    }
    
    method distance_from_origin() {
        sqrt($x * $x + $y * $y);
    }
}

my $p = Point->new(x => 3, y => 4);
is($p->x, 3, "x reader works");
is($p->distance_from_origin, 5, "method works");
is(builtin::reftype($p), 'OBJECT', "Object has correct reftype");
```

### Compatibility Tests
- Ensure transformed code works with existing OO features
- Test interaction with blessed references
- Verify `ref()` and `isa()` work correctly
- Test with existing modules

### Error Cases
```perl
# Missing required param
eval { Point->new(y => 1) };
like($@, qr/Missing required parameter: x/);

# ADJUST validation
class PositivePoint {
    field $x :param;
    ADJUST { die "x must be positive" if $x < 0; }
}
eval { PositivePoint->new(x => -1) };
like($@, qr/x must be positive/);
```

## Advantages of AST Transformation Approach

1. **Simplicity**: No runtime changes needed
2. **Compatibility**: Works with existing Perl OO code
3. **Debugging**: Transformed code is standard Perl
4. **Performance**: No runtime overhead
5. **Incremental**: Can implement features one by one

## Challenges and Solutions

### Challenge 1: Lexical Field Access
**Problem**: In methods, bare `$x` should refer to `$self->{x}`
**Solution**: Track fields in scope and rewrite during parsing

### Challenge 2: Constructor Generation
**Problem**: Need to merge user-defined new() with generated code
**Solution**: Only generate if not present; warn if both exist

### Challenge 3: Multiple ADJUST Blocks
**Problem**: Must execute in declaration order
**Solution**: Collect during parsing, concatenate in order

### Challenge 4: Error Messages
**Problem**: Should match Perl's error messages
**Solution**: Map transformed line numbers back to original

## Example: Complete Transformation

### Input (Perl Class Syntax):
```perl
use feature 'class';

class BankAccount :isa(Account) {
    field $balance :param :reader = 0;
    field $owner :param :reader;
    
    ADJUST {
        die "Balance cannot be negative" if $balance < 0;
    }
    
    method deposit($amount) {
        $balance += $amount;
    }
    
    method withdraw($amount) {
        die "Insufficient funds" if $amount > $balance;
        $balance -= $amount;
    }
}
```

### Output (Transformed AST as Traditional Perl):
```perl
package BankAccount;
use base 'Account';

sub new {
    my $class = shift;
    my %args = @_;
    my $self = bless {}, $class;
    
    # Initialize fields
    $self->{balance} = exists $args{balance} ? $args{balance} : 0;
    $self->{owner} = $args{owner} // die "Missing required parameter: owner";
    
    # ADJUST block
    do {
        my $balance = $self->{balance};
        my $owner = $self->{owner};
        die "Balance cannot be negative" if $balance < 0;
    };
    
    return $self;
}

# Reader methods
sub balance { $_[0]->{balance} }
sub owner { $_[0]->{owner} }

# Methods
sub deposit {
    my $self = shift;
    my ($amount) = @_;
    $self->{balance} += $amount;
}

sub withdraw {
    my $self = shift;
    my ($amount) = @_;
    die "Insufficient funds" if $amount > $self->{balance};
    $self->{balance} -= $amount;
}

1;
```

## Success Criteria

1. All class features work as specified in perlclass
2. Transformed code is valid traditional Perl
3. No runtime performance penalty
4. Clear error messages
5. Full compatibility with existing OO code
6. Pass Perl's class feature test suite

## Next Steps

1. Create ClassParser.java with basic class recognition
2. Implement simple field to hash transformation
3. Add test cases for each feature
4. Iterate on transformation rules
5. Handle edge cases and error conditions

This AST transformation approach will allow us to implement Perl's class features incrementally while maintaining full compatibility with existing code.
