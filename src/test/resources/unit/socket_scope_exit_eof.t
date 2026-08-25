use strict;
use warnings;
use Fcntl qw(F_GETFL F_SETFL O_NONBLOCK);
use Socket qw(AF_UNIX SOCK_STREAM PF_UNSPEC);
use Test::More tests => 4;

sub make_nonblocking {
    my ($socket) = @_;
    my $flags = fcntl($socket, F_GETFL, 0);
    die "F_GETFL: $!" unless defined $flags;
    my $set = fcntl($socket, F_SETFL, $flags | O_NONBLOCK);
    die "F_SETFL: $!" unless defined $set;
}

sub peer_reached_eof {
    my ($socket) = @_;
    make_nonblocking($socket);
    sysread($socket, my $buffer, 1);
    return sysread($socket, $buffer, 1);
}

my $left;
{
    socketpair($left, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair: $!";
    is(syswrite($right, 'x'), 1, 'peer writes before scope exit');
}

is(peer_reached_eof($left), 0,
    'peer observes EOF after last socket owner leaves scope');

my ($hash_left, %owner);
{
    socketpair($hash_left, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair: $!";
    $owner{handle} = $right;
    is(syswrite($owner{handle}, 'y'), 1, 'container-owned peer writes');
}
{
    my $removed = delete $owner{handle};
}
is(peer_reached_eof($hash_left), 0,
    'peer observes EOF after deleted handle result leaves scope');
