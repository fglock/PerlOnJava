use strict;
use warnings;
use IO::Socket::INET;
use Test::More;

my %before = map { $_ => 1 } grep { /^GEN\d+$/ } keys %Symbol::;
my $listener = IO::Socket::INET->new(
    LocalAddr => '127.0.0.1',
    LocalPort => 0,
    Listen => 5,
    ReuseAddr => 1,
    Proto => 'tcp',
);
plan skip_all => "loopback listener unavailable: $!" unless $listener;
my $client = IO::Socket::INET->new(
    PeerAddr => '127.0.0.1',
    PeerPort => $listener->sockport,
    Proto => 'tcp',
) or die "cannot connect to loopback listener: $!";
{
    my $accepted = $listener->accept or die "cannot accept loopback client: $!";
}
close $client;
close $listener;

plan tests => 1;
my @new_gensyms = grep { !$before{$_} } grep { /^GEN\d+$/ } keys %Symbol::;
is_deeply(\@new_gensyms, [], 'anonymous socket gensyms do not become package stash owners');
