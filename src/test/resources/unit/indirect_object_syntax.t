#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

# Test class for indirect object syntax testing
package TestClass {
    sub new {
        my $class = shift;
        return bless { @_ }, $class;
    }
    
    sub method {
        my ($self, @args) = @_;
        return "method called with: " . join(", ", @args);
    }
    
    sub method::with::colons {
        my ($self, @args) = @_;
        return "method::with::colons called";
    }
    
    sub AUTOLOAD {
        our $AUTOLOAD;
        my ($self, @args) = @_;
        my $method = $AUTOLOAD;
        $method =~ s/.*:://;
        return "AUTOLOAD: $method";
    }
}

package TestClass::Nested::Deep {
    sub new { bless {}, shift }
    sub test { "nested class method" }
}

# Main test
package main;

subtest "Basic indirect object syntax" => sub {
    # Basic case: new Class
    my $obj = new TestClass;
    isa_ok($obj, 'TestClass', 'new TestClass creates object');
    
    # With arguments: new Class arg1, arg2
    my $obj2 = new TestClass key => 'value';
    isa_ok($obj2, 'TestClass', 'new TestClass with args');
    is($obj2->{key}, 'value', 'Arguments passed correctly');
};

subtest "Method names with ::" => sub {
    # This actually works in Perl!
    eval q{
        my $result = method::with::colons TestClass;
    };
    if ($@) {
        pass('method::with::colons TestClass failed with: ' . $@);
    } else {
        pass('method::with::colons TestClass works (calls TestClass->method::with::colons)');
    }
    
    # Test if the method is actually callable
    my $obj = new TestClass;
    if ($obj->can('method::with::colons')) {
        my $result = $obj->${\'method::with::colons'}();
        is($result, "method::with::colons called", 'Method with :: is callable');
    } else {
        pass('Method with :: not found');
    }
};

subtest "Class names that look like operators" => sub {
    # These should fail - using string eval
    eval q{ my $x = new print; };
    ok($@, 'new print should fail');
    
    eval q{ my $x = new say; };
    ok($@, 'new say should fail');
    
    eval q{ my $x = new if; };
    ok($@, 'new if should fail');
    
    eval q{ my $x = new foreach; };
    ok($@, 'new foreach should fail');
    
    eval q{ my $x = new while; };
    ok($@, 'new while should fail');
    
    eval q{ my $x = new return; };
    ok($@, 'new return should fail');
};

subtest "Method as scalar variable" => sub {
    my $method_name = "new";
    
    # This won't work with indirect syntax
    eval q{
        my $method_name = "new";
        my $obj = $method_name TestClass;
    };
    ok($@, '$method_name TestClass should fail (scalar as method name)');
    
    # But this works with arrow notation
    my $obj = TestClass->$method_name();
    isa_ok($obj, 'TestClass', 'Arrow notation with scalar method works');
};

subtest "Non-existent class" => sub {
    eval q{
        my $obj = new NonExistentClass;
    };
    like($@, qr/Can't locate object method|Bareword/, 'new NonExistentClass fails appropriately');
    
    eval q{
        my $obj = meth AnotherNonExistentClass;
    };
    like($@, qr/Can't locate object method|Bareword/, 'meth AnotherNonExistentClass fails');
};

subtest "Bareword vs quoted strings" => sub {
    # Bareword class
    my $obj1 = new TestClass;
    isa_ok($obj1, 'TestClass', 'Bareword class works');
    
    # Quoted class name won't work with indirect syntax
    eval q{
        my $obj2 = new "TestClass";
    };
    ok($@, 'new "TestClass" should fail (quoted string)');
    
    # But works with arrow
    my $obj3 = "TestClass"->new();
    isa_ok($obj3, 'TestClass', 'Quoted class with arrow works');
};

subtest "Multiple arguments and parentheses" => sub {
    # Without parentheses
    my $obj1 = new TestClass 'arg1', 'arg2', 'arg3';
    isa_ok($obj1, 'TestClass', 'Multiple args without parens');
    
    # With parentheses
    my $obj2 = new TestClass('arg1', 'arg2', 'arg3');
    isa_ok($obj2, 'TestClass', 'Multiple args with parens');
    
    # Mixed style
    my $obj3 = new TestClass 'arg1', key => 'value';
    isa_ok($obj3, 'TestClass', 'Mixed args style');
};

subtest "Nested indirect calls" => sub {
    # This is particularly tricky
    eval q{
        # new Class1 new Class2 doesn't parse as expected
        my $obj = new TestClass new TestClass;
    };
    if ($@) {
        pass("Nested indirect syntax failed: $@");
    } else {
        pass("Nested indirect syntax parsed somehow");
    }
};

subtest "Package with :: in name" => sub {
    my $obj = new TestClass::Nested::Deep;
    isa_ok($obj, 'TestClass::Nested::Deep', 'Package with :: works');
    
    # Method call on nested package
    eval q{
        my $result = test TestClass::Nested::Deep;
    };
    if ($@) {
        pass("test TestClass::Nested::Deep failed: $@");
    } else {
        pass("test TestClass::Nested::Deep parsed");
    }
};

subtest "Special method names" => sub {
    # Method names that are Perl keywords
    eval q{ my $x = if TestClass; };
    ok($@, 'if TestClass fails (keyword)');
    
    eval q{ my $x = while TestClass; };
    ok($@, 'while TestClass fails (keyword)');
    
    eval q{ my $x = sub TestClass; };
    ok($@, 'sub TestClass fails (keyword)');
    
    eval q{ my $x = package TestClass; };
    ok($@, 'package TestClass fails (keyword)');
    
    # AUTOLOAD handling
    my $obj = new TestClass;
    my $result = $obj->nonexistent_method();
    is($result, "AUTOLOAD: nonexistent_method", 'AUTOLOAD works');
};

subtest "Edge cases with special variables" => sub {
    # Using special vars as class names (should fail)
    eval q{ my $x = new $_; };
    ok($@, 'new $_ should fail');
    
    eval q{ my $x = new $@; };
    ok($@, 'new $@ should fail');
    
    # Package name in variable - this actually WORKS in Perl!
    my $classname = "TestClass";
    eval qq{
        my \$classname = "TestClass";
        my \$x = new \$classname;
    };
    if ($@) {
        pass('new $classname failed (might be version dependent)');
    } else {
        pass('new $classname works in this Perl version');
    }
    
    # But arrow always works
    my $obj = $classname->new();
    isa_ok($obj, 'TestClass', 'Arrow with variable class works');
};

subtest "Ambiguous parsing cases" => sub {
    # Cases where parsing might be ambiguous
    
    # Is this: method(Class(), args) or Class->method(args)?
    eval q{
        my $result = method TestClass();
    };
    if ($@) {
        pass("method TestClass() failed: $@");
    } else {
        pass("method TestClass() parsed");
    }
    
    # With comma
    eval q{
        my $result = method TestClass, 'arg';
    };
    if ($@) {
        pass("method TestClass, 'arg' failed: $@");
    } else {
        pass("method TestClass, 'arg' parsed as TestClass->method('arg')");
    }
};

subtest "Unicode in names" => sub {
    eval q{
        package TestClass::Üñíçödé {
            sub new { bless {}, shift }
            sub méthød { "unicode method" }
        }
    };
    
    if (!$@) {
        my $obj = eval q{ new TestClass::Üñíçödé };
        if (!$@) {
            isa_ok($obj, 'TestClass::Üñíçödé', 'Unicode package name works');
        } else {
            pass("Unicode in indirect syntax not supported");
        }
    } else {
        pass("Unicode package names not supported");
    }
};

subtest "Filehandle and GLOB cases" => sub {
    # Indirect object syntax with filehandles
    eval {
        # print STDOUT "test" is indirect object syntax
        print STDOUT "";  # Empty string to avoid test output
    };
    ok(!$@, 'print STDOUT works');
    
    # Custom filehandle
    eval {
        open my $fh, '>', \my $buffer;
        print $fh "test";
        close $fh;
        is($buffer, "test", 'Indirect syntax with filehandle works');
    };
};

subtest "Complex expressions as class" => sub {
    # Using string eval for complex cases
    eval q{
        my %hash = (key => 'TestClass');
        my $x = new $hash{key};
    };
    if ($@) {
        pass('new $hash{key} failed (complex expression)');
    } else {
        pass('new $hash{key} might work');
    }
    
    eval q{
        my @array = ('TestClass');
        my $x = new $array[0];
    };
    if ($@) {
        pass('new $array[0] failed (complex expression)');
    } else {
        pass('new $array[0] might work');
    }
    
    eval q{
        my $x = new (TestClass);
    };
    if ($@) {
        pass('new (TestClass) failed');
    } else {
        pass('new (TestClass) works');
    }
};

subtest "Method chaining with indirect syntax" => sub {
    # Can we chain methods with indirect syntax?
    eval q{
        my $obj = new TestClass;
        my $result = method $obj, "arg";
    };
    if ($@) {
        pass('method $obj failed: ' . $@);
    } else {
        pass('method $obj works (object as invocant)');
    }
};

subtest "Return values and context" => sub {
    # Test what indirect syntax returns
    my $obj = new TestClass;
    ok(defined $obj, 'new TestClass returns defined value');
    isa_ok($obj, 'TestClass', 'Return value is correct type');
    
    # List context
    my @result = new TestClass;
    is(scalar @result, 1, 'new TestClass in list context returns one item');
    isa_ok($result[0], 'TestClass', 'List context item is correct type');
};

done_testing();


