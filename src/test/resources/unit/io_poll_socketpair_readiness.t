use v5.10;
use strict;
use warnings;
use Test::More;
use IO::Poll qw(POLLIN);
use IO::Socket::UNIX;
use Socket qw(PF_UNIX SOCK_STREAM);

my ($left, $right) = IO::Socket::UNIX->socketpair(PF_UNIX, SOCK_STREAM, 0);
plan skip_all => "UNIX socketpair is unavailable: $!" unless $left && $right;

my $poll = IO::Poll->new;
$poll->mask($left, POLLIN);
is($poll->poll(0), 0, 'an idle socketpair endpoint is not readable');

done_testing;
