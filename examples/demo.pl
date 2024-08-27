# A sample Perl script with a bit of everything
# use feature 'say';

# Test variable assignment and modification
my $a = 15;
my $x = $a;
print "not " if $x != 15; say "ok 1 - \$x is 15";

$a = 12;
print "not " if $a != 12; say "ok 2 - \$a is 12";

# Test anonymous subroutine
my $anon_sub = sub { return @_ };
my @result = $anon_sub->(1, 2, 3);
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


############################
#  Subroutines

# named subroutine with typeglob assignment

*x = sub { return "<@_>" };
my $result = &x(123);
print "not " if $result ne "<123>"; say "ok 8 - named subroutine with typeglob returned '$result'";

@_ = (345);
$result = &x;
print "not " if $result ne "<345>"; say "ok 9 - named subroutine with typeglob, no parameters, returned '$result'";

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

