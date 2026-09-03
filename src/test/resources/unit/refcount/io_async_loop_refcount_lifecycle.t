#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use B qw(svref_2object);

{
    package IOAsyncRefcountLifecycle;
    sub new { bless {}, shift }
}

# IO::Async keeps its loop in a package-global singleton while callers retain
# a lexical reference.  B's inspection object must not manufacture a durable
# third owner, and removing the singleton must release exactly that owner.
my $loop = IOAsyncRefcountLifecycle->new;
$IOAsyncRefcountLifecycle::ONE_TRUE_LOOP = $loop;

is svref_2object($loop)->REFCNT, 2,
    'lexical and singleton each count as one IO::Async loop owner';

undef $IOAsyncRefcountLifecycle::ONE_TRUE_LOOP;

is svref_2object($loop)->REFCNT, 1,
    'removing the singleton leaves only the lexical loop owner';

done_testing;
