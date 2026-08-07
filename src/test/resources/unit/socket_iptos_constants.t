#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;

my $loaded = eval q{
    use Socket 2.010 qw(
        IPTOS_LOWDELAY IPTOS_THROUGHPUT IPTOS_RELIABILITY IPTOS_MINCOST
    );
    1;
};

my %expected = (
    IPTOS_LOWDELAY   => 0x10,
    IPTOS_THROUGHPUT => 0x08,
    IPTOS_RELIABILITY => 0x04,
    IPTOS_MINCOST    => 0x02,
);

plan skip_all => "Socket vendor cannot import IPTOS constants: $@" unless $loaded;

my %actual;
for my $name (sort keys %expected) {
    no strict 'refs';
    $actual{$name} = eval { &{"Socket::$name"}() };
    plan skip_all => "Socket vendor omits $name: $@" if $@;
}

for my $name (sort keys %expected) {
    is $actual{$name}, $expected{$name}, "$name has its portable wire value";
}

done_testing;
