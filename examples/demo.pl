# A comprehensive demonstration of Perl language features
# and their implementation in PerlOnJava
#
# This file exists solely for demonstration and educational purposes.
# It is NOT part of the automated test suite.
#
# Running this demo:
#   ./jperl examples/demo.pl
#
# Note: The actual test suite is located in src/test/resources
# and is executed during the build process via:
#   - Maven: mvn test
#   - Gradle: gradle test
#
# Features demonstrated:
#   - Variable and list assignments
#   - Subroutines and references
#   - Array and hash operations
#   - Object-oriented programming
#   - Built-in functions
#   - Context sensitivity
#   - Perl idioms and best practices
#

use 5.40.0;
use Test::More;
use strict;
use warnings;

subtest 'Variable Assignment' => sub {
    my $a = 15;
    my $x = $a;
    is($x, 15, '$x assigned correctly');

    $a = 12;
    is($a, 12, '$a modified correctly');
};

subtest 'List Assignment in Scalar Context' => sub {
    my @array = (10, 20, 30, 40, 50);
    my $count = () = @array;
    is($count, 5, 'List assignment returns correct count');

    @array = ();
    $count = () = @array;
    is($count, 0, 'Empty list assignment returns 0');

    my $var1 = 10;
    my $var2 = 20;
    my $var3 = 30;
    $count = () = ($var1, $var2, $var3);
    is($count, 3, 'List assignment with variables returns correct count');

    my ($first, $second);
    @array = (1, 2, 3, 4, 5);
    $count = ($first, $second) = @array;
    is($count, 5, 'List assignment with non-empty left-hand side count');
    is($first, 1, 'First variable assigned correctly');
    is($second, 2, 'Second variable assigned correctly');
};

subtest 'List Assignment with Arrays and Hashes' => sub {
    my ($first, $second, @lvalue_array);
    my @array = (1, 2, 3, 4, 5);
    my $count = ($first, $second, @lvalue_array) = @array;
    is($count, 5, 'List assignment with lvalue array count');
    is($first, 1, 'First scalar assigned correctly');
    is($second, 2, 'Second scalar assigned correctly');
    is("@lvalue_array", "3 4 5", 'Array assigned correctly');

    my %lvalue_hash;
    @array = (10, 20, 30, 40, 50);
    $count = ($first, $second, %lvalue_hash) = @array;
    is($count, 5, 'List assignment with lvalue hash count');
    is($first, 10, 'First scalar with hash assigned correctly');
    is($second, 20, 'Second scalar with hash assigned correctly');
    is($lvalue_hash{30}, 40, 'Hash assigned correctly');
};

subtest 'Anonymous Subroutines' => sub {
    my @result = sub { return @_ }->(1, 2, 3);
    is("@result", "1 2 3", 'Anonymous sub direct call');

    my $anon_sub = sub { return @_ };
    @result = $anon_sub->(1, 2, 3);
    is("@result", "1 2 3", 'Anonymous sub stored in variable');
};

subtest 'Eval and Do Blocks' => sub {
    my $a = 12;
    eval '$a = $a + 1';
    is($a, 13, 'eval string modifies variable');

    do {
        if (1) { $a = 123 }
        elsif (3) { $a = 345 }
        else { $a = 456 }
    };
    is($a, 123, 'do block with conditionals');
};

subtest 'References and Data Structures' => sub {
    my $href = { a => 'hash-value' };
    is($href->{a}, 'hash-value', 'Hash reference access');

    my $aref = [4, 5];
    is($aref->[1], 5, 'Array reference access');
};

subtest 'Array Operations' => sub {
    my $aref = [4, 5];
    push @$aref, 6;
    is("@$aref", "4 5 6", 'Push operation');

    unshift @$aref, 3;
    is("@$aref", "3 4 5 6", 'Unshift operation');

    my $popped = pop @$aref;
    is($popped, 6, 'Pop operation return value');
    is("@$aref", "3 4 5", 'Array after pop');

    my $shifted = shift @$aref;
    is($shifted, 3, 'Shift operation return value');
    is("@$aref", "4 5", 'Array after shift');
};

subtest 'Splice Operations' => sub {
    my $b = [1, 2, 3, 4, 5];
    splice @$b, 1, 1, 7;
    is("@$b", "1 7 3 4 5", 'splice replace one element');

    splice @$b, 1, 0, 8, 9;
    is("@$b", "1 8 9 7 3 4 5", 'splice insert elements');

    my $removed = splice @$b, 2, 2;
    is("@$b", "1 8 3 4 5", 'splice remove elements');
    is($removed, "7", 'splice returned removed elements');

    splice @$b, -2, 1, 6;
    is("@$b", "1 8 3 6 5", 'splice with negative offset');

    splice @$b, -3, -1;
    is("@$b", "1 8 5", 'splice with negative length');
};

subtest 'List Processing Functions' => sub {
    my @array = (1, 2, 3, 4, 5);
    my @mapped = map { $_ * 2 } @array;
    is("@mapped", "2 4 6 8 10", 'Map doubles elements');

    @mapped = map { $_ % 2 == 0 ? $_ * 2 : $_ } @array;
    is("@mapped", "1 4 3 8 5", 'Map with conditional');

    my @filtered = grep { $_ % 2 == 0 } @array;
    is("@filtered", "2 4", 'Grep even numbers');

    @filtered = grep { $_ > 3 } @array;
    is("@filtered", "4 5", 'Grep numbers greater than 3');
};

subtest 'Sorting' => sub {
    our ($a, $b);
    my @unsorted = (5, 3, 1, 4, 2);
    my @sorted = sort { $a <=> $b } @unsorted;
    is("@sorted", "1 2 3 4 5", 'Numerical ascending sort');

    @sorted = sort { $b <=> $a } @unsorted;
    is("@sorted", "5 4 3 2 1", 'Numerical descending sort');

    @sorted = sort { length($a) <=> length($b) } qw(foo foobar bar);
    is("@sorted", "foo bar foobar", 'Sort by string length');

    @sorted = sort { $a cmp $b } qw(zebra apple monkey);
    is("@sorted", "apple monkey zebra", 'Alphabetical sort');
};

subtest 'Named Subroutines and Typeglobs' => sub {
    *x = sub { return "<@_>" };
    my $result = &x(123);
    is($result, "<123>", 'Named subroutine with typeglob');

    @_ = (345);
    $result = &x;
    is($result, "<345>", 'Subroutine using existing @_');

    sub modify_argument { $_[0]++ }
    my $v = 13;
    modify_argument($v);
    is($v, 14, 'Subroutine argument aliasing');

    sub CONST () { "VALUE" }
    is(CONST . "2", "VALUE2", 'Constant subroutine concatenation');
    is((CONST => "2")[0], "CONST", 'Constant in fat arrow context');
};

package Obj {
    sub meth { 123 }
}

package Obj2 {
    sub meth2 { 456 }
    our @ISA = ("Obj");
}

subtest 'Object-Oriented Features' => sub {

    my $obj = bless {}, "Obj";
    is($obj->meth, 123, 'Method call on object');
    is(Obj->meth, 123, 'Class method call');
    is(Obj::->meth, 123, 'Alternative class method syntax');

    my $obj2 = bless {}, "Obj2";
    is($obj2->meth, 123, 'Inherited method call');
    ok($obj2->isa("Obj"), 'isa() check');
    ok($obj2 isa "Obj", 'isa operator');
    is($obj2->can("meth")->($obj2), 123, 'can() with inherited method');
};

done_testing();
