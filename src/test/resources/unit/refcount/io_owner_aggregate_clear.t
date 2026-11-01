use strict;
use warnings;
use IO::Select;
use Socket qw(AF_UNIX SOCK_STREAM);
use Test::More tests => 4;

sub make_hash_owner {
    socketpair(my $peer, my $handle, AF_UNIX, SOCK_STREAM, 0)
        or die "socketpair hash: $!";
    my %owners = (handle => $handle);
    return ($peer, \%owners);
}

sub make_array_owner {
    socketpair(my $peer, my $handle, AF_UNIX, SOCK_STREAM, 0)
        or die "socketpair array: $!";
    my @owners = ($handle);
    return ($peer, \@owners);
}

sub peer_reached_eof {
    my ($peer) = @_;
    my $selector = IO::Select->new($peer);
    return 1 unless $selector->can_read(0.1);
    sysread($peer, my $buffer, 1);
    return 1 unless $selector->can_read(0.1);
    return sysread($peer, $buffer, 1);
}

my ($hash_peer, $hash_owners) = make_hash_owner();
is(syswrite($hash_owners->{handle}, 'h'), 1, 'hash-owned socket is live');
undef %$hash_owners;
is(peer_reached_eof($hash_peer), 0,
    'clearing the final hash slot releases its socket owner');

my ($array_peer, $array_owners) = make_array_owner();
is(syswrite($array_owners->[0], 'a'), 1, 'array-owned socket is live');
@$array_owners = ();
is(peer_reached_eof($array_peer), 0,
    'clearing the final array slot releases its socket owner');
