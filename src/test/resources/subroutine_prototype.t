use strict;
use Test::More;

# Empty prototype - regular subroutine
sub empty_proto () { 42 }
is(empty_proto(), 42, "Empty prototype works like a constant");
is(empty_proto, 42, "Empty prototype allows call without parens");

# Scalar prototype
sub scalar_proto ($) { $_[0] }
is(scalar_proto(123), 123, "Scalar prototype accepts single argument");
is(scalar_proto("abc"), "abc", "Scalar prototype works with strings");

# List prototype
sub list_proto (@) { join(",", @_) }
is(list_proto(1,2,3), "1,2,3", "List prototype accepts multiple arguments");

# Array reference prototype
sub array_ref_proto (\@) { $_[0]->[0] }
my @arr = (1,2,3);
is(array_ref_proto(@arr), 1, "Array reference prototype works");

# Hash reference prototype
sub hash_ref_proto (\%) { keys %{$_[0]} }
my %hash = (a => 1, b => 2);
is(hash_ref_proto(%hash), 2, "Hash reference prototype works");

# Scalar reference prototype
sub scalar_ref_proto (\$) { ${$_[0]} }
my $val = 42;
is(scalar_ref_proto($val), 42, "Scalar reference prototype works");

# Code reference prototype
sub code_ref_proto (&) { $_[0]->() }
is(code_ref_proto{ 99 }, 99, "Code reference prototype works");

# Multiple argument prototype
sub multi_proto ($$) { $_[0] + $_[1] }
is(multi_proto(2, 3), 5, "Multiple argument prototype works");

# Optional argument prototype
sub optional_proto (;$) { defined $_[0] ? $_[0] : "default" }
is(optional_proto(), "default", "Optional argument prototype works without arg");
is(optional_proto("test"), "test", "Optional argument prototype works with arg");

# Mixed prototypes
sub mixed_proto ($@) { $_[0] . ":" . join(",", @_[1..$#_]) }
is(mixed_proto("head", 1,2,3), "head:1,2,3", "Mixed prototype works");

# Prototype with slurpy parameter
sub slurpy_proto ($;@) { $_[0] . ":" . join(",", @_[1..$#_]) }
is(slurpy_proto("x"), "x:", "Slurpy prototype works with single arg");
is(slurpy_proto("x", 1,2), "x:1,2", "Slurpy prototype works with multiple args");

# Context-sensitive prototype
sub context_proto (_) { wantarray ? (1,2,3) : "scalar" }
my @list = context_proto(1);
is_deeply(\@list, [1,2,3], "Context prototype works in list context");
is(scalar context_proto(1), "scalar", "Context prototype works in scalar context");

# Bracketed reference prototype - accepts array or hash
sub bracketed_proto (\[@%]) { ref $_[0] }
@arr = (1,2,3);
%hash = (a => 1);
is(bracketed_proto(@arr), "ARRAY", "Bracketed prototype accepts array reference");
is(bracketed_proto(%hash), "HASH", "Bracketed prototype accepts hash reference");

# Multiple bracketed references
sub multi_bracketed_proto (\[@%] \[@%]) { ref($_[0]) . "," . ref($_[1]) }
is(multi_bracketed_proto(@arr, %hash), "ARRAY,HASH", "Multiple bracketed prototypes work");
is(multi_bracketed_proto(%hash, @arr), "HASH,ARRAY", "Bracketed prototypes work in any order");

# Optional bracketed reference
sub optional_bracketed_proto (;\[@%]) { defined $_[0] ? ref($_[0]) : "none" }
is(optional_bracketed_proto(), "none", "Optional bracketed prototype works without arg");
is(optional_bracketed_proto(@arr), "ARRAY", "Optional bracketed prototype works with array");
is(optional_bracketed_proto(%hash), "HASH", "Optional bracketed prototype works with hash");

subtest "Plus (+) prototype behavior" => sub {
    sub plus_proto (+) { ref($_[0]) || "SCALAR" }

    my @plus_arr = (1,2,3);
    my %plus_hash = (a => 1);
    my $aref = [1,2,3];
    my $href = {x => 1};

    is(plus_proto(@plus_arr), "ARRAY", "accepts literal array as reference");
    is(plus_proto(%plus_hash), "HASH", "accepts literal hash as reference");
    is(plus_proto(42), "SCALAR", "forces scalar context on numbers");
    is(plus_proto("xyz"), "SCALAR", "forces scalar context on strings");
    is(plus_proto($aref), "ARRAY", "preserves array reference");
    is(plus_proto($href), "HASH", "preserves hash reference");
};

done_testing();

