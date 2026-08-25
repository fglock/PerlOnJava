use strict;
use warnings;
use Fcntl qw(F_GETFL F_SETFL O_NONBLOCK);
use Socket qw(AF_UNIX SOCK_STREAM PF_UNSPEC);
use Symbol qw(gensym);
use Test::More tests => 11;

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

sub observed_fileno {
    my ($socket) = @_;
    return fileno($socket);
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
    ok defined observed_fileno($removed),
        'temporary argument alias preserves the removed socket';
}
is(peer_reached_eof($hash_left), 0,
    'peer observes EOF after deleted handle result leaves scope');

my $gensym_left;
{
    my $right = gensym;
    socketpair($gensym_left, $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair gensym: $!";
    is(syswrite($right, 'z'), 1, 'unstashed gensym socket writes');
}
is(peer_reached_eof($gensym_left), 0,
    'peer observes EOF after unstashed gensym socket leaves scope');

my $array_left;
{
    socketpair($array_left, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair array: $!";
    my @owners = ($right);
    is(syswrite($owners[0], 'a'), 1, 'array-owned socket writes');
}
is(peer_reached_eof($array_left), 0,
    'peer observes EOF after socket-owning array leaves scope');

my $scope_hash_left;
{
    socketpair($scope_hash_left, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair hash: $!";
    my %owners = (handle => $right);
    is(syswrite($owners{handle}, 'h'), 1, 'hash-owned socket writes');
}
is(peer_reached_eof($scope_hash_left), 0,
    'peer observes EOF after socket-owning hash leaves scope');
