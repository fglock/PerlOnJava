#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 4;

{
    my @readonly = (10, 11);
    Internals::SvREADONLY(@readonly, 1);
    my $ok = eval { splice @readonly, 1, 0, (); 1 };
    ok(!$ok, 'splice rejects a read-only array');
    like($@, qr/^Modification of a read-only value/,
         'splice reports the read-only modification');
}

{
    package NegativeFetchSizeRegression;
    sub TIEARRAY { bless {}, $_[0] }
    sub FETCHSIZE { -1 }
}

tie my @negative_size, 'NegativeFetchSizeRegression';
my $ok = eval { scalar @negative_size; 1 };
ok(!$ok, 'a negative tied-array FETCHSIZE is rejected');
like($@, qr/^FETCHSIZE returned a negative value/,
     'negative tied-array FETCHSIZE reports the error');
