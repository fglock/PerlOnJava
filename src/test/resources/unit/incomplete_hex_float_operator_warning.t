#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 3;

for my $source ('0xp3', '5p3') {
    my $warning;
    {
        local $SIG{__WARN__} = sub { $warning = shift };
        eval $source;
    }
    like($warning, qr/Missing operator before "p3"/,
        "incomplete numeric form $source warns about the trailing exponent marker");
}

my $suppressed_warning;
{
    no warnings 'syntax';
    local $SIG{__WARN__} = sub { $suppressed_warning = shift };
    eval '5p3';
}
ok(!defined $suppressed_warning, 'no warnings syntax suppresses the bareword warning');
