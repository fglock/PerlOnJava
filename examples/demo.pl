# A sample Perl script with a bit of everything
# use 5.32.0;
# use feature 'say';
# use feature 'isa';

# Test variable assignment and modification
my $a = 15;
my $x = $a;
print "not " if $x != 15; say "ok 1 - \$x is 15";

$a = 12;
print "not " if $a != 12; say "ok 2 - \$a is 12";

# Test anonymous subroutine
my @result = sub { return @_ }->(1, 2, 3);
print "not " if "@result" ne "1 2 3"; say "ok 3 - anonymous subroutine returned '@result'";

my $anon_sub = sub { return @_ };
@result = $anon_sub->(1, 2, 3);
print "not " if "@result" ne "1 2 3"; say "ok 3 - anonymous subroutine returned '@result'";

# Test eval string
eval '$a = $a + 1';
print "not " if $a != 13; say "ok 4 - eval string modified \$a to 13";

# Test do block with conditional statements
do {
    if (1) { $a = 123 }
    elsif (3) { $a = 345 }
    else { $a = 456 }
};
print "not " if $a != 123; say "ok 5 - do block executed if block, \$a is 123";

# Test hash and array references
$a = { a => 'hash-value' };
print "not " if $a->{a} ne 'hash-value'; say "ok 6 - hash value is '$a->{a}'";

my $b = [ 4, 5 ];
print "not " if $b->[1] != 5; say "ok 7 - array value is $b->[1]";

push @$b, 6;
print "not " if $#$b != 2; say "ok 8 - push increased array count";

unshift @$b, 3;
print "not " if "@$b" ne "3 4 5 6"; say "ok 9 - unshift";

$a = pop @$b;
print "not " if "@$b" ne "3 4 5"; say "ok 10 - pop";
print "not " if $a != 6; say "ok 11 - pop";

$a = shift @$b;
print "not " if "@$b" ne "4 5"; say "ok 12 - shift";
print "not " if $a != 3; say "ok 13 - pop";


############################
# Splice tests

splice @$b, 1, 1, 7;
print "not " if "@$b" ne "4 7"; say "ok 14 - splice replace one element";

splice @$b, 1, 0, 8, 9;
print "not " if "@$b" ne "4 8 9 7"; say "ok 15 - splice insert elements";

$a = splice @$b, 2, 2;
print "not " if "@$b" ne "4 8"; say "ok 16 - splice remove elements";
print "not " if "$a" ne "7"; say "ok 17 - splice removed elements";

splice @$b, 1;
print "not " if "@$b" ne "4"; say "ok 18 - splice remove from offset";

splice @$b, 0, 0, 1, 2, 3;
print "not " if "@$b" ne "1 2 3 4"; say "ok 19 - splice insert at beginning";

splice @$b;
print "not " if "@$b" ne ""; say "ok 20 - splice remove all elements";

# Negative offset and length
$b = [1, 2, 3, 4, 5];
splice @$b, -2, 1, 6;
print "not " if "@$b" ne "1 2 3 6 5"; say "ok 21 - splice with negative offset";

splice @$b, -3, -1;
print "not " if "@$b" ne "1 2 5"; say "ok 22 - splice with negative length";

############################
# Map tests

my @array = (1, 2, 3, 4, 5);
my @mapped = map { $_ * 2 } @array;
print "not " if "@mapped" ne "2 4 6 8 10"; say "ok 23 - map doubled each element";

@mapped = map { $_ % 2 == 0 ? $_ * 2 : $_ } @array;
print "not " if "@mapped" ne "1 4 3 8 5"; say "ok 24 - map conditionally doubled even elements";

############################
# Grep tests

my @filtered = grep { $_ % 2 == 0 } @array;
print "not " if "@filtered" ne "2 4"; say "ok 25 - grep filtered even elements";

@filtered = grep { $_ > 3 } @array;
print "not " if "@filtered" ne "4 5"; say "ok 26 - grep filtered elements greater than 3";

############################
# Sort tests

{
    # Note: `sort` uses the global $a, $b variables.
    # In order for sort to work, we have to mask the lexical $a, $b that we have declared before.
    our ($a, $b);   # Hide the existing `my` variables

    my @unsorted = (5, 3, 1, 4, 2);
    my @sorted = sort { $a <=> $b } @unsorted;
    print "not " if "@sorted" ne "1 2 3 4 5"; say "ok 27 - sort in numerical ascending order";
    
    @sorted = sort { $b <=> $a } @unsorted;
    print "not " if "@sorted" ne "5 4 3 2 1"; say "ok 28 - sort in numerical descending order";
    
    @sorted = sort { length($a) <=> length($b) } qw(foo foobar bar);
    print "not " if "@sorted" ne "foo bar foobar"; say "ok 29 - sort by string length";
    
    @sorted = sort { $a cmp $b } qw(zebra apple monkey);
    print "not " if "@sorted" ne "apple monkey zebra"; say "ok 30 - sort in alphabetical order";
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

