use strict;
use warnings;
use IO::Socket::INET;
use Test::More;

my %before = map { $_ => 1 } grep { /^GEN\d+$/ } keys %Symbol::;
{
    my $socket = IO::Socket::INET->new(
        LocalAddr => '127.0.0.1',
        LocalPort => 0,
        Listen => 5,
        ReuseAddr => 1,
        Proto => 'tcp',
    );
    plan skip_all => "loopback listener unavailable: $!" unless $socket;
}

plan tests => 1;
my @new_gensyms = grep { !$before{$_} } grep { /^GEN\d+$/ } keys %Symbol::;
is_deeply(\@new_gensyms, [], 'anonymous socket gensyms do not become package stash owners');
