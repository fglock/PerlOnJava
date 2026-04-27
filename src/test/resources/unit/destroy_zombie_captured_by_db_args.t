#!/usr/bin/env perl
# Regression test for the refcount-accounting asymmetry between
# RuntimeArray.add(RuntimeScalar) and RuntimeScalar.addToArray(RuntimeArray).
#
# Anon-array-literal construction legitimately increfs per-element (balanced
# by createReferenceWithTrackedElements at literal end). Arg-list construction
# must NOT incref per-element, because the args array is popped off argsStack
# without walking its elements to decref.
#
# If a future maintainer "audits for symmetry" and adds an incref to
# RuntimeScalar.addToArray, this test detects the regression: the captured
# zombie ref never DESTROYs at the @capture = () clear, because the leaked
# arg-passing incref keeps refCount > 0.
#
# Full context: comment on RuntimeScalar.addToArray; dev/design/perf-dbic-safe-port.md.
# Fails DBIC t/storage/txn_scope_guard.t#18 ("Preventing *MULTIPLE* DESTROY")
# if regressed.

use strict;
use warnings;
use Test::More tests => 3;

our $destroy_count = 0;

{
    package Guard;
    sub new { bless { id => $_[1] }, "Guard" }
    sub DESTROY { $main::destroy_count++ }
}

sub inner {
    package DB;
    my $frnum = 0;
    while (my @frame = caller(++$frnum)) {
        push @main::capture, @DB::args;
    }
}

sub call_with_guard { inner() }

our @capture;

{
    my $g = Guard->new("zombie");
    call_with_guard($g);
}

is($destroy_count, 0, 'zombie still alive, captured by @capture via @DB::args');
is(scalar @capture, 1, '@capture holds one zombie ref');

# This clear is what should trigger the DESTROY in Perl.
# If RuntimeScalar.addToArray incref is wrongly present, refCount stays > 0
# here and DESTROY fires at process exit (too late).
@capture = ();

is($destroy_count, 1, 'DESTROY fires synchronously from @capture = ()')
    or diag(
        "If this failed, someone likely reintroduced an incref in\n".
        "RuntimeScalar.addToArray -> the PLAIN_ARRAY branch. Revert that\n".
        "or, alternatively, walk-and-decref the args array in popArgs().\n".
        "See the long comment on RuntimeScalar.addToArray for rationale."
    );
