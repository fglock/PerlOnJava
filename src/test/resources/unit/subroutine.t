# use v5.38.0;
use Symbol;
use strict;
use Test::More;
use feature 'say';

############################
#  Subroutines

# named subroutine with typeglob assignment
*x = sub { return "<@_>" };
my $result = &x(123);
is($result, "<123>", "named subroutine with typeglob returns correct value");

@_ = (345);
$result = &x;
is($result, "<345>", "named subroutine with typeglob and no parameters reuses @_");

# &name calls the subroutine reusing existing @_
@_ = (456, "ABC");
&x;

ok(exists &x, "subroutine exists");
ok(defined &x, "subroutine is defined");

ok(!exists &xnot, "non-existent subroutine does not exist");
ok(!defined &xnot, "non-existent subroutine is not defined");

# named subroutine with Symbol assignment
my $sym_ref = qualify_to_ref("A", "B");
diag("x is " . \&x);
diag("sym_ref is " . $sym_ref);
*$sym_ref = \&x;

$result = "not called";
eval ' $result = B::A(123) ';
is($result, "<123>", "named subroutine with Symbol returns correct value");

# named subroutine
sub modify_argument { $_[0]++ }
my $v = 13;
modify_argument($v);
is($v, 14, "subroutine list argument is an alias to the argument");

$v = 13;
modify_argument $v;
is($v, 14, "subroutine scalar argument is an alias to the argument");

# constant subroutine
sub CONST () { "VALUE" }
$v = CONST . "2";
is($v, "VALUE2", "constant subroutine concatenation works");

$v = CONST;
is($v, "VALUE", "constant subroutine direct call works");

package Other {
    use Test::More;
    sub CONST () { "OTHER" }
    is(CONST(), "OTHER", "constant subroutine defined in other package");
}
is(CONST, "VALUE", "constant subroutine was not overwritten by other package");

# subroutine call operator precedence
sub no_proto { "VALUE" }
$v = no_proto . "2";
is($v, "VALUE2", "subroutine without prototype concatenation works");

$v = no_proto;
is($v, "VALUE", "subroutine without prototype direct call works");

# return from odd places
sub return_odd { $_[0] ? (2, return 3, 4) : (5) }
is(return_odd(1), 4, "return from inside list works");

# Additional test cases for & subroutine sigil
sub example { return "<@_>" }
my $sub_ref = \&example;

# Direct calls
$result = &example(789);
is($result, "<789>", "direct call with &example(789)");

@_ = ("test", "values");
$result = &example;
is($result, "<test values>", "direct call reusing @_");

# Indirect calls using subroutine reference
$result = $sub_ref->(101);
is($result, "<101>", "indirect call with sub_ref->()");

@_ = ("another", "test");
$result = &$sub_ref;
is($result, "<another test>", "indirect call reusing @_");

# Using block syntax
$result = &{$sub_ref}(303);
is($result, "<303>", "block syntax call");

@_ = ("block", "syntax");
$result = &{$sub_ref};
is($result, "<block syntax>", "block syntax reusing @_");

# Reference to subroutine
my $ref_to_sub = \&example;
$result = &$ref_to_sub(404);
is($result, "<404>", "reference to subroutine call");

@_ = ("reference", "test");
$result = &$ref_to_sub;
is($result, "<reference test>", "reference to subroutine reusing @_");

# lvalue subroutine
sub lv :lvalue { $result }
lv = 13;
is($result, "13", "lvalue subroutine is assignable");

# Tests for goto &sub
sub original_sub { return "original" }
sub redirect_sub { goto &original_sub }
$result = redirect_sub();
is($result, "original", "goto &sub redirects correctly");

sub first_sub { return "first" }
sub second_sub { goto &first_sub }
$result = second_sub();
is($result, "first", "goto &sub redirects to first_sub");

# Test with parameters
sub add_one { my ($num) = @_; return $num + 1 }
sub add_one_redirect { goto &add_one }
$result = add_one_redirect(5);
is($result, 6, "goto &sub with parameters");

# Test for subroutine hoisting
my $hoist_result = hoisted_sub(10);
is($hoist_result, 20, "hoisted subroutine call works");

sub hoisted_sub {
    my ($value) = @_;
    return $value * 2;
}

# Test for subroutine hoisting with predeclared prototype
sub hoisted_with_prototype($);
my ($hoist_proto_result, $extra) = (hoisted_with_prototype 15, 25);
is($hoist_proto_result, 30, "hoisted subroutine with prototype");
is($extra, 25, "prototype correctly handles extra parameter");

sub hoisted_with_prototype($) {
    my ($value) = @_;
    return $value * 2;
}

done_testing();
