#!/usr/bin/env perl
#
# Regression test for the Filter::Util::Call source-filter scoping
# bug.
#
# Before the fix, a source filter installed by an outer `use Foo`
# would leak into any module that Foo::import() transitively
# require'd: the nested file's `use` statements consumed the
# `filterInstalledDuringUse` flag (and the filter rewrote the nested
# file instead of the original).  Real Perl scopes filters per
# compilation unit (PL_compiling / PL_rsfp_filters); PerlOnJava now
# does the same via FilterUtilCall.saveAndReset() / restore() at the
# executePerlCode boundary.
#
# This test reproduces the exact bug pattern WITHOUT any external
# CPAN deps: a filter module whose import() does (a) install a
# source filter via filter_add, then (b) require a second module —
# mimicking what `use Spiffy -Base;` does (`filter_add` + then
# `require Exporter::Heavy`).  After the require, the parent file's
# next line MUST still be filtered.

use strict;
use warnings;
use Test::More tests => 4;

# ---------------------------------------------------------------------
# A bundled bystander module that the filter module's import() will
# pull in via require.  Its job is to be parsed *during* the filter
# installer's import() — exactly the position where Exporter::Heavy
# would be loaded by Spiffy.  Picking something already in @INC
# guarantees the test doesn't depend on any external module.
# ---------------------------------------------------------------------
my $bystander = 'Cwd';   # not loaded by Test::More; has multiple `use` statements
                          # in its source whose parsing would consume the filter
                          # flag if the bug is present.

# ---------------------------------------------------------------------
# Define an inline filter module on the fly: when imported, it
# (1) installs a per-line s/REPLACEME/ok_marker/ filter, then
# (2) requires the bystander module so the bug pattern triggers.
# ---------------------------------------------------------------------
{
    package InlineFilter;
    use Filter::Util::Call;

    sub import {
        my (undef, $bystander) = @_;   # @_ is ($class, @args) for `use $class @args`
        filter_add(sub {
            my $status = filter_read();
            s/REPLACEME/ok_marker/g if $status > 0;
            return $status;
        });
        # This require is what triggered the leak: Spiffy did the same
        # thing with Exporter::export -> require Exporter::Heavy.
        eval "require $bystander; 1" or die "require $bystander failed: $@";
    }
}
# Pretend the inline package was loaded from a file so `use
# InlineFilter` inside the eval below finds it without scanning @INC.
$INC{'InlineFilter.pm'} = __FILE__;

# ---------------------------------------------------------------------
# Test 1 — the filter rewrites code in *this* file (the parent),
# even though InlineFilter::import did a require in the middle of
# its work.  Without the per-compilation-unit scoping, the filter's
# install flag would have been consumed by the bystander's `use`
# statements and the parent's REPLACEME would not have been
# transformed.
# ---------------------------------------------------------------------
my $compiled = eval <<"EOPL";
package InlineFilterTest;
use InlineFilter '$bystander';
sub answer { return "REPLACEME" }   # will become "ok_marker" if filter applied
1;
EOPL
ok $compiled, "filter-installing module imports without error"
    or diag "compile error: \$\@ = $@";

SKIP: {
    skip "package didn't compile", 2 unless $compiled;

    # Test 2 — filter actually fired on the parent's source.
    my $got = InlineFilterTest::answer();
    is $got, 'ok_marker',
       'filter rewrote `REPLACEME` in the parent file (filter survived nested require)';

    # Test 3 — filter did NOT leak into the nested require: the
    # bystander module is unmodified and still works.
    my $cwd = eval { Cwd::cwd() };
    ok defined($cwd) && length($cwd),
       'nested require\'d module unaffected by filter (Cwd::cwd works)'
        or diag "Cwd::cwd failed: $@";
}

# ---------------------------------------------------------------------
# Test 4 — after the eval finishes, the filter's chain must NOT
# leak into *this* file: REPLACEME in this outer scope is left
# untouched.
# ---------------------------------------------------------------------
my $literal = "REPLACEME";   # would become "ok_marker" if the filter leaked
is $literal, 'REPLACEME',
   'filter scoped to the eval STRING — does not leak to caller';
