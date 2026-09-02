use v5.14;
use strict;
use warnings;
use Test::More;
use IO::Socket;
use Socket qw(AF_UNIX SOCK_STREAM PF_UNSPEC);

my ($left, $right) = IO::Socket->new->socketpair(
    AF_UNIX, SOCK_STREAM, PF_UNSPEC,
) or die "IO::Socket socketpair failed: $!";

my $left_method_fd = $left->fileno;
my $right_method_fd = $right->fileno;

ok defined $left_method_fd, 'IO::Socket left handle has a method fileno';
ok defined $right_method_fd, 'IO::Socket right handle has a method fileno';
is $left_method_fd, fileno($left), 'method fileno matches core fileno on left';
is $right_method_fd, fileno($right), 'method fileno matches core fileno on right';
isnt $left_method_fd, $right_method_fd, 'socketpair ends have distinct descriptors';

is syswrite($right, 'ready'), 5, 'write through IO::Socket handle';
my $buffer = '';
is sysread($left, $buffer, 5), 5, 'read through IO::Socket handle';
is $buffer, 'ready', 'socketpair transport remains usable after fileno';

my $captured_peer;
my $connect = sub {
    my ($self_socket, $peer_socket) = IO::Socket->new->socketpair(
        AF_UNIX, SOCK_STREAM, PF_UNSPEC,
    ) or die "captured socketpair failed: $!";
    $captured_peer = $peer_socket;
    return $self_socket;
};

my $self_socket = $connect->();
ok defined $captured_peer->fileno,
    'socket peer assigned to a captured lexical retains method fileno after callback return';
is syswrite($self_socket, 'captured'), 8,
    'write through callback-returned socket handle';
$buffer = '';
is sysread($captured_peer, $buffer, 8), 8,
    'read through closure-captured socket peer';
is $buffer, 'captured',
    'closure-captured socket peer remains usable after callback return';

done_testing;
