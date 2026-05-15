#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Scalar::Util qw(weaken);

BEGIN {
    eval { require Internals; 1 }
        or plan skip_all => 'Internals not available (core refcount helpers)';
}

{
    package GTAC_Object;
    sub new { bless {}, shift }
}

my $strong = GTAC_Object->new;
my $weak = $strong;
weaken($weak);

sub target { return }

sub trampoline {
    unshift @_, $weak;
    goto &target;
}

my $baseline = Internals::SvREFCNT($strong);
trampoline(123) for 1..3;

is Internals::SvREFCNT($strong), $baseline,
    'goto &sub cleans tail-call argument ownership';
ok defined($weak), 'weak reference remains live while strong ref exists';

done_testing;
