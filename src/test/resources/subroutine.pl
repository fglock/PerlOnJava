use v5.38.0;
use Symbol;
use strict;

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

# named subroutine with Symbol assignment

my $sym_ref = Symbol::qualify_to_ref("A", "B");
say "# x is " . \&x;    # x is CODE
say "# sym_ref is " . $sym_ref;  # sym_ref is GLOB
*$sym_ref = \&x;

$result = "not called";
eval ' $result = B::A(123) ';
print "not " if $result ne "<123>"; say "ok # named subroutine with Symbol returned '$result'";

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

$v = CONST;
print "not " if $v ne "VALUE"; say "ok # constant subroutine returned $v";

package Other {
    sub CONST () { "OTHER" }
    print "not " if CONST ne "OTHER"; say "ok # constant subroutine defined in other package";
}
print "not " if CONST ne "VALUE"; say "ok # constant subroutine was not overwritten";

# subroutine call operator precedence

sub no_proto { "VALUE" }
$v = no_proto . "2";
print "not" if $v ne "VALUE2"; say "ok # subroutine without prototype returned $v";

$v = no_proto;
print "not" if $v ne "VALUE"; say "ok # subroutine without prototype returned $v";

# return from odd places

sub return_odd { $_[0] ? (2, return 3, 4) : (5) }

print "not" if return_odd(1) != 4; say "ok # return from inside list works";

# Additional test cases for & subroutine sigil

sub example { return "<@_>" }
my $sub_ref = \&example;

# Direct calls
$result = &example(789);
print "not " if $result ne "<789>"; say "ok # direct call with &example(789) returned '$result'";

@_ = ("test", "values");  # Initialize @_ with values
$result = &example;
print "not " if $result ne "<test values>"; say "ok # direct call with &example reused @_ and returned '$result'";

# Indirect calls using subroutine reference
$result = $sub_ref->(101);
print "not " if $result ne "<101>"; say "ok # indirect call with sub_ref->(101) returned '$result'";

@_ = ("another", "test");
$result = &$sub_ref;
print "not " if $result ne "<another test>"; say "ok # indirect call with &$sub_ref reused @_ and returned '$result'";

# Using block syntax
$result = &{$sub_ref}(303);
print "not " if $result ne "<303>"; say "ok # block syntax with &{$sub_ref}(303) returned '$result'";

@_ = ("block", "syntax");
$result = &{$sub_ref};
print "not " if $result ne "<block syntax>"; say "ok # block syntax with &{$sub_ref} reused @_ and returned '$result'";

# Reference to subroutine
my $ref_to_sub = \&example;
$result = &$ref_to_sub(404);
print "not " if $result ne "<404>"; say "ok # reference to subroutine with &$ref_to_sub(404) returned '$result'";

@_ = ("reference", "test");
$result = &$ref_to_sub;
print "not " if $result ne "<reference test>"; say "ok # reference to subroutine with &$ref_to_sub reused @_ and returned '$result'";

