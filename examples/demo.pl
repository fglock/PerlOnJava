# A sample Perl script with a bit of everything
#
# Note:
#
#   - This Perl scripts is not part of the automated test suite.
#     It is provided for educational and illustrative purposes.
#
#   - Automated tests for Perl scripts are located in the src/test/resources directory.
#     These test files are executed during the build process by Maven/Gradle to ensure the correctness of the Perl code.
#
#   - To run the automated tests manually, you can use the following commands:
#     - For Maven: `mvn test`
#     - For Gradle: `gradle test`
#
#   These commands will compile the Java code, run the Java and Perl tests, and generate test reports.
#

use 5.40.0;
# use feature 'say';
# use feature 'isa';

# Test variable assignment and modification
my $a = 15;
my $x = $a;
print "not " if $x != 15; say "ok # \$x is 15";

$a = 12;
print "not " if $a != 12; say "ok # \$a is 12";


############################
#  List assignment in scalar context

{
# List assignment in scalar context returns the number of elements produced by the expression on the right side of the assignment

# Test with a static list
my @array = (10, 20, 30, 40, 50);
my $count = () = @array;
print "not " if $count != 5; say "ok # List assignment in scalar context returned '$count'";

@array = ();
$count = () = @array;
print "not " if $count != 0; say "ok # List assignment in scalar context returned '$count'";

# Test with a list containing variables
my $var1 = 10;
my $var2 = 20;
my $var3 = 30;
$count = () = ($var1, $var2, $var3);
print "not " if $count != 3; say "ok # List assignment with variables in scalar context returned '$count'";

# Test with a mixed list of variables and literals
my $var4 = 40;
@array = ($var1, $var2, $var3, $var4, 50, 60);
$count = () = @array;
print "not " if $count != 6; say "ok # List assignment with mixed variables and literals in scalar context returned '$count'";

# Test with non-empty left-hand side
my ($first, $second);
@array = (1, 2, 3, 4, 5);
$count = ($first, $second) = @array;
print "not " if $count != 5; say "ok # List assignment with non-empty left-hand side returned '$count'";
print "not " if $first != 1; say "ok # First variable assigned correctly with value '$first'";
print "not " if $second != 2; say "ok # Second variable assigned correctly with value '$second'";

@array = (10, 20);
$count = ($first, $second) = @array;
print "not " if $count != 2; say "ok # List assignment with non-empty left-hand side returned '$count'";
print "not " if $first != 10; say "ok # First variable assigned correctly with value '$first'";
print "not " if $second != 20; say "ok # Second variable assigned correctly with value '$second'";

@array = (100);
$second = 20;
$count = ($first, $second) = @array;
print "not " if $count != 1; say "ok # List assignment with non-empty left-hand side returned '$count'";
print "not " if $first != 100; say "ok # First variable assigned correctly with value '$first'";
print "not " if defined $second; say "ok # Second variable is undefined as expected";
}

############################
#  List assignment in scalar context with lvalue array and hash

{

# Test with non-empty left-hand side including an array
my ($first, $second, @lvalue_array);
my @array = (1, 2, 3, 4, 5);
my $count = ($first, $second, @lvalue_array) = @array;
print "not " if $count != 5; say "ok # List assignment with lvalue array returned '$count'";
print "not " if $first != 1; say "ok # First variable assigned correctly with value '$first'";
print "not " if $second != 2; say "ok # Second variable assigned correctly with value '$second'";
print "not " if "@lvalue_array" ne "3 4 5"; say "ok # Lvalue array assigned correctly with values '@lvalue_array'";

@array = (10, 20);
$count = ($first, $second, @lvalue_array) = @array;
print "not " if $count != 2; say "ok # List assignment with lvalue array returned '$count'";
print "not " if $first != 10; say "ok # First variable assigned correctly with value '$first'";
print "not " if $second != 20; say "ok # Second variable assigned correctly with value '$second'";
print "not " if "@lvalue_array" ne ""; say "ok # Lvalue array assigned correctly with values '@lvalue_array'";

# Test with non-empty left-hand side including a hash
my %lvalue_hash;
@array = (10, 20, 30, 40, 50);
$count = ($first, $second, %lvalue_hash) = @array;
print "not " if $count != 5; say "ok # List assignment with lvalue hash returned '$count'";
print "not " if $first != 10; say "ok # First variable assigned correctly with value '$first'";
print "not " if $second != 20; say "ok # Second variable assigned correctly with value '$second'";
print "not " if keys %lvalue_hash != 2 || $lvalue_hash{30} != 40; say "ok # Lvalue hash assigned correctly with keys and values: @{[ %lvalue_hash ]} keys: @{[ scalar keys %lvalue_hash ]}";

@array = (10, 20);
$count = ($first, $second, %lvalue_hash) = @array;
print "not " if $count != 2; say "ok # List assignment with lvalue hash returned '$count'";
print "not " if $first != 10; say "ok # First variable assigned correctly with value '$first'";
print "not " if $second != 20; say "ok # Second variable assigned correctly with value '$second'";
print "not " if keys %lvalue_hash != 0; say "ok # Lvalue hash assigned correctly with keys and values";

}

###################
# Basic Syntax

# Test anonymous subroutine
my @result = sub { return @_ }->(1, 2, 3);
print "not " if "@result" ne "1 2 3"; say "ok # anonymous subroutine returned '@result'";

my $anon_sub = sub { return @_ };
@result = $anon_sub->(1, 2, 3);
print "not " if "@result" ne "1 2 3"; say "ok # anonymous subroutine returned '@result'";

# Test eval string
eval '$a = $a + 1';
print "not " if $a != 13; say "ok # eval string modified \$a to 13";

# Test do block with conditional statements
do {
    if (1) { $a = 123 }
    elsif (3) { $a = 345 }
    else { $a = 456 }
};
print "not " if $a != 123; say "ok # do block executed if block, \$a is 123";

# Test hash and array references
$a = { a => 'hash-value' };
print "not " if $a->{a} ne 'hash-value'; say "ok # hash value is '$a->{a}'";

my $b = [ 4, 5 ];
print "not " if $b->[1] != 5; say "ok # array value is $b->[1]";

push @$b, 6;
print "not " if $#$b != 2; say "ok # push increased array count";

unshift @$b, 3;
print "not " if "@$b" ne "3 4 5 6"; say "ok # unshift";

$a = pop @$b;
print "not " if "@$b" ne "3 4 5"; say "ok # pop";
print "not " if $a != 6; say "ok # pop";

$a = shift @$b;
print "not " if "@$b" ne "4 5"; say "ok # shift";
print "not " if $a != 3; say "ok # pop";


############################
# Splice tests

splice @$b, 1, 1, 7;
print "not " if "@$b" ne "4 7"; say "ok # splice replace one element";

splice @$b, 1, 0, 8, 9;
print "not " if "@$b" ne "4 8 9 7"; say "ok # splice insert elements";

$a = splice @$b, 2, 2;
print "not " if "@$b" ne "4 8"; say "ok # splice remove elements";
print "not " if "$a" ne "7"; say "ok # splice removed elements";

splice @$b, 1;
print "not " if "@$b" ne "4"; say "ok # splice remove from offset";

splice @$b, 0, 0, 1, 2, 3;
print "not " if "@$b" ne "1 2 3 4"; say "ok # splice insert at beginning";

splice @$b;
print "not " if "@$b" ne ""; say "ok # splice remove all elements";

# Negative offset and length
$b = [1, 2, 3, 4, 5];
splice @$b, -2, 1, 6;
print "not " if "@$b" ne "1 2 3 6 5"; say "ok # splice with negative offset";

splice @$b, -3, -1;
print "not " if "@$b" ne "1 2 5"; say "ok # splice with negative length";

############################
# Map tests

my @array = (1, 2, 3, 4, 5);
my @mapped = map { $_ * 2 } @array;
print "not " if "@mapped" ne "2 4 6 8 10"; say "ok # map doubled each element";

@mapped = map { $_ % 2 == 0 ? $_ * 2 : $_ } @array;
print "not " if "@mapped" ne "1 4 3 8 5"; say "ok # map conditionally doubled even elements";

############################
# Grep tests

my @filtered = grep { $_ % 2 == 0 } @array;
print "not " if "@filtered" ne "2 4"; say "ok # grep filtered even elements";

@filtered = grep { $_ > 3 } @array;
print "not " if "@filtered" ne "4 5"; say "ok # grep filtered elements greater than 3";

############################
# Sort tests

{
    # Note: `sort` uses the global $a, $b variables.
    # In order for sort to work, we have to mask the lexical $a, $b that we have declared before.
    our ($a, $b);   # Hide the existing `my` variables

    my @unsorted = (5, 3, 1, 4, 2);
    my @sorted = sort { $a <=> $b } @unsorted;
    print "not " if "@sorted" ne "1 2 3 4 5"; say "ok # sort in numerical ascending order";
    
    @sorted = sort { $b <=> $a } @unsorted;
    print "not " if "@sorted" ne "5 4 3 2 1"; say "ok # sort in numerical descending order";
    
    @sorted = sort { length($a) <=> length($b) } qw(foo foobar bar);
    print "not " if "@sorted" ne "foo bar foobar"; say "ok # sort by string length";
    
    @sorted = sort { $a cmp $b } qw(zebra apple monkey);
    print "not " if "@sorted" ne "apple monkey zebra"; say "ok # sort in alphabetical order";
}

############################
#  Subroutines

# named subroutine with typeglob assignment

*x = sub { return "<@_>" };
my $result = &x(123);
print "not " if $result ne "<123>"; say "ok # named subroutine with typeglob returned '$result'";

@_ = (345);
$result = &x;
print "not " if $result ne "<345>"; say "ok # named subroutine with typeglob, no parameters, returned '$result'";

# &name calls the subroutine reusing existing @_

@_ = (456, "ABC");
&x;

# named subroutine

sub modify_argument { $_[0]++ }
my $v = 13;
modify_argument($v);
print "not " if $v != 14; say "ok # subroutine list argument is an alias to the argument; returned $v";
$v = 13;
modify_argument $v;
print "not " if $v != 14; say "ok # subroutine scalar argument is an alias to the argument; returned  $v";

# constant subroutine

sub CONST () { "VALUE" }
$v = CONST . "2";
print "not " if $v ne "VALUE2"; say "ok # constant subroutine returned $v";

$v = CONST => "2";
print "not " if $v ne "CONST"; say "ok # constant subroutine returned $v";

package Other {
    sub CONST () { "OTHER" }
    print "not " if CONST ne "OTHER"; say "ok # constant subroutine defined in other package";
}
print "not " if CONST ne "VALUE"; say "ok # constant subroutine was not overwritten";

# subroutine call operator precedence

sub no_proto { "VALUE" }
$v = no_proto . "2";
print "not" if $v ne "VALUE2"; say "ok # subroutine without prototype returned $v";

$v = no_proto or "2";
print "not" if $v ne "VALUE"; say "ok # subroutine without prototype returned $v";


############################
#  Objects

# bless() and ref()

$v = {};
print "not" if ref($v) ne "HASH"; say "ok # unblessed reference returns data type";

bless $v, "Pkg";
print "not" if ref($v) ne "Pkg"; say "ok # blessed reference returns package name";

# method is a CODE

my $obj = "123";
my $method = sub { "called" };
print "not" if $obj->$method ne "called"; say "ok # CODE method is called";

# method is in a class

$obj = bless {}, "Obj";

package Obj { sub meth { 123 } }

print "not" if $obj->meth ne "123"; say "ok # method is resolved and called";

print "not" if Obj->meth ne "123"; say "ok # class method is resolved and called";

print "not" if Obj::->meth ne "123"; say "ok # class method is resolved and called, alternate syntax";

# inheritance

package Obj2 { sub meth2 { 456 } our @ISA = ("Obj"); }

$obj = bless {}, "Obj2";

print "not" if $obj->meth ne "123"; say "ok # method is resolved and called in the parent class";

print "not" if !$obj->isa("Obj"); say "ok # object isa() superclass";

print "not" if !($obj isa "Obj"); say "ok # object isa() superclass";

print "not" if $obj->can("meth")->($obj) ne "123"; say "ok # object can() returns method from superclass";

5;    # return value

