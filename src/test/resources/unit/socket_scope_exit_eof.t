use strict;
use warnings;
use Fcntl qw(F_GETFL F_SETFL O_NONBLOCK);
use Socket qw(AF_UNIX SOCK_STREAM PF_UNSPEC);
use Test::More tests => 2;

my $left;
{
    socketpair($left, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair: $!";
    is(syswrite($right, 'x'), 1, 'peer writes before scope exit');
}

my $flags = fcntl($left, F_GETFL, 0);
die "F_GETFL: $!" unless defined $flags;
my $set = fcntl($left, F_SETFL, $flags | O_NONBLOCK);
die "F_SETFL: $!" unless defined $set;

sysread($left, my $buffer, 1);
my $read = sysread($left, $buffer, 1);
is($read, 0, 'peer observes EOF after last socket owner leaves scope');
