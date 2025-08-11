# A sample Perl script with a bit of everything
#
# Note:
#
#   This Perl script is part of the project's examples and demonstrates various Perl features.
#   It is located in the src/test/resources directory.
#
#   Perl test files in src/test/resources are executed during the build process by Maven/Gradle.
#   This ensures that the Perl scripts are tested for correctness as part of the continuous integration pipeline.
#
#   To run the tests manually, you can use the following commands:
#     - For Maven: `mvn test`
#     - For Gradle: `gradle test`
#
#   These commands will compile the Java code, run the Java and Perl tests, and generate test reports.
#
#   Ensure that any new Perl scripts added to src/test/resources follow the project's testing conventions.
#

use 5.32.0;
use feature 'say';
use feature 'isa';
use Test::More;

# Test variable assignment and modification
subtest "Variable assignment and modification" => sub {
    my $a = 15;
    my $x = $a;
    is($x, 15, "\$x is 15");

    $a = 12;
    is($a, 12, "\$a is 12");
};

############################
#  List assignment in scalar context

subtest "List assignment in scalar context" => sub {
    # List assignment in scalar context returns the number of elements produced by the expression on the right side of the assignment

    # Test with a static list
    my @array = (10, 20, 30, 40, 50);
    my $count = () = @array;
    is($count, 5, "List assignment in scalar context returned '$count'");

    @array = ();
    $count = () = @array;
    is($count, 0, "List assignment in scalar context returned '$count'");

    # Test with a list containing variables
    my $var1 = 10;
    my $var2 = 20;
    my $var3 = 30;
    $count = () = ($var1, $var2, $var3);
    is($count, 3, "List assignment with variables in scalar context returned '$count'");

    # Test with a mixed list of variables and literals
    my $var4 = 40;
    @array = ($var1, $var2, $var3, $var4, 50, 60);
    $count = () = @array;
    is($count, 6, "List assignment with mixed variables and literals in scalar context returned '$count'");

    # Test with non-empty left-hand side
    my ($first, $second);
    @array = (1, 2, 3, 4, 5);
    $count = ($first, $second) = @array;
    is($count, 5, "List assignment with non-empty left-hand side returned '$count'");
    is($first, 1, "First variable assigned correctly with value '$first'");
    is($second, 2, "Second variable assigned correctly with value '$second'");

    @array = (10, 20);
    $count = ($first, $second) = @array;
    is($count, 2, "List assignment with non-empty left-hand side returned '$count'");
    is($first, 10, "First variable assigned correctly with value '$first'");
    is($second, 20, "Second variable assigned correctly with value '$second'");

    @array = (100);
    $second = 20;
    $count = ($first, $second) = @array;
    is($count, 1, "List assignment with non-empty left-hand side returned '$count'");
    is($first, 100, "First variable assigned correctly with value '$first'");
    ok(!defined $second, "Second variable is undefined as expected");
};

############################
#  List assignment in scalar context with lvalue array and hash

subtest "List assignment with lvalue array and hash" => sub {
    # Test with non-empty left-hand side including an array
    my ($first, $second, @lvalue_array);
    my @array = (1, 2, 3, 4, 5);
    my $count = ($first, $second, @lvalue_array) = @array;
    is($count, 5, "List assignment with lvalue array returned '$count'");
    is($first, 1, "First variable assigned correctly with value '$first'");
    is($second, 2, "Second variable assigned correctly with value '$second'");
    is("@lvalue_array", "3 4 5", "Lvalue array assigned correctly with values '@lvalue_array'");

    @array = (10, 20);
    $count = ($first, $second, @lvalue_array) = @array;
    is($count, 2, "List assignment with lvalue array returned '$count'");
    is($first, 10, "First variable assigned correctly with value '$first'");
    is($second, 20, "Second variable assigned correctly with value '$second'");
    is("@lvalue_array", "", "Lvalue array assigned correctly with values '@lvalue_array'");

    # Test with non-empty left-hand side including a hash
    my %lvalue_hash;
    @array = (10, 20, 30, 40, 50);
    $count = ($first, $second, %lvalue_hash) = @array;
    is($count, 5, "List assignment with lvalue hash returned '$count'");
    is($first, 10, "First variable assigned correctly with value '$first'");
    is($second, 20, "Second variable assigned correctly with value '$second'");
    ok(keys %lvalue_hash == 2 && $lvalue_hash{30} == 40, "Lvalue hash assigned correctly with keys and values: @{[ %lvalue_hash ]} keys: @{[ scalar keys %lvalue_hash ]}");

    @array = (10, 20);
    $count = ($first, $second, %lvalue_hash) = @array;
    is($count, 2, "List assignment with lvalue hash returned '$count'");
    is($first, 10, "First variable assigned correctly with value '$first'");
    is($second, 20, "Second variable assigned correctly with value '$second'");
    is(keys %lvalue_hash, 0, "Lvalue hash assigned correctly with keys and values");
};

###################
# Basic Syntax

subtest "Basic syntax tests" => sub {
    my $a = 12;  # Initial value for eval test

    # Test anonymous subroutine
    my @result = sub { return @_ }->(1, 2, 3);
    is("@result", "1 2 3", "anonymous subroutine returned '@result'");

    my $anon_sub = sub { return @_ };
    @result = $anon_sub->(1, 2, 3);
    is("@result", "1 2 3", "anonymous subroutine returned '@result'");

    # Test eval string
    eval '$a = $a + 1';
    is($a, 13, "eval string modified \$a to 13");

    # Test do block with conditional statements
    do {
        if (1) { $a = 123 }
        elsif (3) { $a = 345 }
        else { $a = 456 }
    };
    is($a, 123, "do block executed if block, \$a is 123");

    # Test hash and array references
    $a = { a => 'hash-value' };
    is($a->{a}, 'hash-value', "hash value is '$a->{a}'");

    my $b = [ 4, 5 ];
    is($b->[1], 5, "array value is $b->[1]");

    push @$b, 6;
    is($#$b, 2, "push increased array count");

    unshift @$b, 3;
    is("@$b", "3 4 5 6", "unshift");

    $a = pop @$b;
    is("@$b", "3 4 5", "pop");
    is($a, 6, "pop");

    $a = shift @$b;
    is("@$b", "4 5", "shift");
    is($a, 3, "pop");
};

############################
# Splice tests

subtest "Splice tests" => sub {
    my $b = [ 4, 5 ];
    my $a;

    splice @$b, 1, 1, 7;
    is("@$b", "4 7", "splice replace one element");

    splice @$b, 1, 0, 8, 9;
    is("@$b", "4 8 9 7", "splice insert elements");

    $a = splice @$b, 2, 2;
    is("@$b", "4 8", "splice remove elements");
    is("$a", "7", "splice removed elements");

    splice @$b, 1;
    is("@$b", "4", "splice remove from offset");

    splice @$b, 0, 0, 1, 2, 3;
    is("@$b", "1 2 3 4", "splice insert at beginning");

    splice @$b;
    is("@$b", "", "splice remove all elements");

    # Negative offset and length
    $b = [1, 2, 3, 4, 5];
    splice @$b, -2, 1, 6;
    is("@$b", "1 2 3 6 5", "splice with negative offset");

    splice @$b, -3, -1;
    is("@$b", "1 2 5", "splice with negative length");
};

############################
# Map tests

subtest "Map tests" => sub {
    my @array = (1, 2, 3, 4, 5);
    my @mapped = map { $_ * 2 } @array;
    is("@mapped", "2 4 6 8 10", "map doubled each element");

    @mapped = map { $_ % 2 == 0 ? $_ * 2 : $_ } @array;
    is("@mapped", "1 4 3 8 5", "map conditionally doubled even elements");
};

############################
# Grep tests

subtest "Grep tests" => sub {
    my @array = (1, 2, 3, 4, 5);
    my @filtered = grep { $_ % 2 == 0 } @array;
    is("@filtered", "2 4", "grep filtered even elements");

    @filtered = grep { $_ > 3 } @array;
    is("@filtered", "4 5", "grep filtered elements greater than 3");
};

############################
# Sort tests

subtest "Sort tests" => sub {
    # Note: `sort` uses the global $a, $b variables.
    # In order for sort to work, we have to mask the lexical $a, $b that we have declared before.
    our ($a, $b);   # Hide the existing `my` variables

    # XXX BUG our() variables access is lost when they are aliased

    my @unsorted = (5, 3, 1, 4, 2);
    my @sorted = sort { $::a <=> $::b } @unsorted;
    is("@sorted", "1 2 3 4 5", "sort in numerical ascending order");

    @sorted = sort { $::b <=> $::a } @unsorted;
    is("@sorted", "5 4 3 2 1", "sort in numerical descending order");

    @sorted = sort { length($::a) <=> length($::b) } qw(foo foobar bar);
    is("@sorted", "foo bar foobar", "sort by string length");

    @sorted = sort { $::a cmp $::b } qw(zebra apple monkey);
    is("@sorted", "apple monkey zebra", "sort in alphabetical order");

    @sorted = sort qw(zebra apple monkey);
    is("@sorted", "apple monkey zebra", "sort without block");
};

############################
#  Objects

subtest "Object tests" => sub {
    # bless() and ref()
    my $v = {};
    is(ref($v), "HASH", "unblessed reference returns data type");

    bless $v, "Pkg";
    is(ref($v), "Pkg", "blessed reference returns package name");

    # method is a CODE
    my $obj = "123";
    my $method = sub { "called" };
    is($obj->$method, "called", "CODE method is called");

    # method is in a class
    $obj = bless {}, "Obj";

    package Obj { sub meth { 123 } }

    is($obj->meth, "123", "method is resolved and called");

    is(Obj->meth, "123", "class method is resolved and called");

    is(Obj::->meth, "123", "class method is resolved and called, alternate syntax");

    # inheritance
    package Obj2 { sub meth2 { 456 } our @ISA = ("Obj"); }

    $obj = bless {}, "Obj2";

    is($obj->meth, "123", "method is resolved and called in the parent class");

    ok($obj->isa("Obj"), "object isa() superclass");

    ok($obj isa "Obj", "object isa() superclass");

    is($obj->can("meth")->($obj), "123", "object can() returns method from superclass");
};

done_testing();

5;    # return value
