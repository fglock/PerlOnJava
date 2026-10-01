use strict;
use warnings;
use Test::More tests => 1;

eval q{
    package Local::EscapingTie;
    sub TIESCALAR {
        next;
        bless {}, shift;
    }
    package main;
    tie my $value, 'Local::EscapingTie';
};
like($@, qr/^Can't "next" outside a loop block at /,
    'control flow cannot escape a tie constructor');
