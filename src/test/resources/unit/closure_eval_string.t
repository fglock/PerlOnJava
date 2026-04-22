use strict;
use warnings;
use Test::More;

# Regression test for a closure-capture bug exposed by
# Net::SSH::Perl's modulino Makefile.PL.
#
# When `sub do_it { ... %WM ... }` is a named (package) sub that
# closes over a file-scope `my %WM`, PerlOnJava implements the
# capture via a BEGIN-package alias registered by SubroutineParser
# at compile time. The alias must stay alive until the owning
# `my %WM = (...)` runs at runtime (at which point `retrieveBeginHash`
# takes ownership and removes the alias).
#
# The bug: `eval STRING` in a sibling sub would (a) re-install the
# same alias in the global hash at entry, and (b) unconditionally
# remove it in its `finally` block on exit, destroying the shared
# object. When `my %WM = (...)` then ran, it saw an empty global
# alias map and created a brand new RuntimeHash, disconnected from
# the one the named sub had already captured. Result: `sub do_it`
# saw an empty hash.
#
# This file must keep the `my` declarations at file scope (so named
# subs parsed below can legally close over them) and must call the
# `eval STRING`-containing sub BEFORE the `my` assignment runs.

# ---- Hash case ----
our @hash_order;
hash_foo();                              # forward call, runs eval STRING
my %HASH = ( NAME => 'Foo', X => 'Y' );  # declared AFTER the call
hash_do();
sub hash_do  { push @hash_order, join(",", sort keys %HASH); }
sub hash_foo { eval("1;"); push @hash_order, "foo"; }

is_deeply(\@hash_order, ["foo", "NAME,X"],
    "named sub hash closure survives eval STRING in a sibling sub");

# ---- Array case ----
our @arr_order;
arr_foo();
my @ARR = (1, 2, 3);
arr_do();
sub arr_do  { push @arr_order, join(",", @ARR); }
sub arr_foo { eval("1;"); push @arr_order, "foo"; }

is_deeply(\@arr_order, ["foo", "1,2,3"],
    "named sub array closure survives eval STRING in a sibling sub");

# ---- Scalar case ----
our @sca_order;
sca_foo();
my $SCA = "hello";
sca_do();
sub sca_do  { push @sca_order, defined($SCA) ? $SCA : "undef"; }
sub sca_foo { eval("1;"); push @sca_order, "foo"; }

is_deeply(\@sca_order, ["foo", "hello"],
    "named sub scalar closure survives eval STRING in a sibling sub");

done_testing();
