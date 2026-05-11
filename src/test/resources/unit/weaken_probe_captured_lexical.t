#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;

eval q{
    package WeakProbeCapturedLexical;
    use B qw(svref_2object);
    use Scalar::Util qw(weaken);

    our $destroyed = 0;
    my $held;

    sub DESTROY { $destroyed++ }

    sub make {
        $held = bless {}, __PACKAGE__;
        our $ONE_TRUE;
        $ONE_TRUE ||= $held;
        undef $ONE_TRUE;
    }

    sub probe_refcount {
        my ($object) = @_;
        @_ = ();
        weaken($object);
        svref_2object($object)->REFCNT;
    }

    sub run {
        make();
        probe_refcount($held);
        return ($destroyed, defined($held) ? 1 : 0);
    }

    1;
} or die $@;

my ($destroyed, $defined) = WeakProbeCapturedLexical::run();
is $destroyed, 0, 'weakening a refcount probe copy does not destroy captured file lexical';
is $defined, 1, 'captured file lexical still holds the object';

done_testing;
