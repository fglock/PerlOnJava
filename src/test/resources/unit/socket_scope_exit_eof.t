use strict;
use warnings;
use Fcntl qw(F_GETFL F_SETFL O_NONBLOCK);
use IO::Handle ();
use Scalar::Util qw(weaken);
use Socket qw(AF_UNIX SOCK_STREAM PF_UNSPEC);
use Symbol qw(gensym);
use Test::More tests => 32;

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

my $alias_left;
{
    my $outer;
    {
        socketpair($alias_left, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
            or die "socketpair alias: $!";
        $outer = $right;
    }
    is(syswrite($outer, 's'), 1,
        'scalar alias keeps socket open after source scope exit');
}
is(peer_reached_eof($alias_left), 0,
    'peer observes EOF after final scalar alias leaves scope');

my ($capture_left, $keeper);
{
    socketpair($capture_left, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair capture: $!";
    $keeper = sub { fileno($right) };
}
ok defined $keeper->(), 'closure capture keeps socket open after lexical scope exit';
undef $keeper;
is(peer_reached_eof($capture_left), 0,
    'peer observes EOF after final socket-capturing closure is released');

{
    package Local::SocketHandle;
    our @ISA = qw(IO::Handle);
}

my $blessed_left;
{
    my $right = gensym;
    socketpair($blessed_left, $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair blessed: $!";
    bless $right, 'Local::SocketHandle';
    is(syswrite($right, 'b'), 1, 'blessed socket glob writes');
}
is(peer_reached_eof($blessed_left), 0,
    'peer observes EOF after blessed socket glob leaves scope');

{
    package Local::SocketOwner;

    sub close_handle {
        my $self = shift;
        return unless my $handle = delete $self->{handle};
        return fileno($handle);
    }
}

my $method_left;
{
    socketpair($method_left, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair method: $!";
    my $owner = bless {handle => $right}, 'Local::SocketOwner';
    ok defined $owner->close_handle,
        'method-local delete result preserves socket during the call';
}
is(peer_reached_eof($method_left), 0,
    'peer observes EOF after method-local deleted socket leaves scope');

{
    package Local::Reactor;

    sub new { bless {io => {}}, shift }

    sub io {
        my ($self, $handle, $cb) = @_;
        $self->{io}{fileno($handle)} = {cb => $cb};
        return $self;
    }

    sub remove {
        my ($self, $handle) = @_;
        return !!delete $self->{io}{fileno($handle)};
    }

    package Local::Stream;

    sub new {
        my ($class, $handle, $reactor) = @_;
        return bless {handle => $handle, reactor => $reactor, timeout => 15}, $class;
    }

    sub timeout {
        my ($self, $timeout) = @_;
        $self->{timeout} = $timeout if defined $timeout;
        return $self;
    }

    sub close {
        my $self = shift;
        return unless my $reactor = $self->{reactor};
        return unless my $handle = delete $self->timeout(0)->{handle};
        return $reactor->remove($handle);
    }

    sub DESTROY { shift->close }
}

my $stream_left;
{
    socketpair($stream_left, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair stream: $!";
    my $reactor = Local::Reactor->new;
    my $stream = Local::Stream->new($right, $reactor);
    my $weak_stream = $stream;
    weaken $weak_stream;
    $reactor->io($right, sub { $weak_stream });
    ok $stream->close, 'stream-style close removes the reactor callback';
}
is(peer_reached_eof($stream_left), 0,
    'peer observes EOF after stream-style close releases method aliases');

{
    package Local::SocketReturner;

    sub return_handle {
        my ($class, $handle) = @_;
        return $handle;
    }
}

my ($returned_left, $returned_right);
{
    socketpair($returned_left, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair return: $!";
    $returned_right = Local::SocketReturner->return_handle($right);
}
is(syswrite($returned_right, 'r'), 1,
    'explicitly returned socket remains open for the caller');
undef $returned_right;
is(peer_reached_eof($returned_left), 0,
    'peer observes EOF after explicitly returned socket is released');

sub construct_aliased_socket {
    my ($left_ref) = @_;
    my $socket = gensym;
    my $constructor_alias = $socket;
    socketpair($$left_ref, $socket, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair constructed: $!";
    return $constructor_alias;
}

my $constructed_left;
my $constructed_right = construct_aliased_socket(\$constructed_left);
is(syswrite($constructed_right, 'c'), 1,
    'socket returned through pre-IO constructor aliases remains open');
undef $constructed_right;
is(peer_reached_eof($constructed_left), 0,
    'pre-IO constructor aliases do not become phantom socket owners');

sub take_outer_argument_alias {
    my ($argument_ref) = @_;
    my $handle = $$argument_ref;
    return $handle;
}

sub pass_socket_through_nested_call {
    return take_outer_argument_alias(\$_[0]);
}

my ($nested_left, $nested_right);
{
    socketpair($nested_left, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair nested argument: $!";
    $nested_right = pass_socket_through_nested_call($right);
}
is(syswrite($nested_right, 'n'), 1,
    'socket copied from an outer active argument frame remains open');
undef $nested_right;
is(peer_reached_eof($nested_left), 0,
    'outer active argument alias does not become a phantom socket owner');

sub initialize_socket_argument {
    my ($handle, $left_ref) = @_;
    socketpair($$left_ref, $_[0], AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair initialized argument: $!";
    return take_outer_argument_alias(\$_[0]);
}

my ($argument_left, $argument_right);
{
    my $uninitialized = gensym;
    $argument_right = initialize_socket_argument($uninitialized, \$argument_left);
}
is(syswrite($argument_right, 'a'), 1,
    'socket initialized in an outer argument frame remains open');
undef $argument_right;
is(peer_reached_eof($argument_left), 0,
    'initialized outer argument aliases do not become phantom socket owners');

sub return_socket_list {
    my ($left_ref) = @_;
    socketpair($$left_ref, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair list return: $!";
    return ($right, 'peer');
}

my ($list_left, $list_right, $list_peer);
($list_right, $list_peer) = return_socket_list(\$list_left);
is($list_peer, 'peer', 'socket list return preserves companion values');
is(syswrite($list_right, 'l'), 1,
    'socket assigned from a returned temporary list remains open');
undef $list_right;
is(peer_reached_eof($list_left), 0,
    'returned temporary list does not retain a phantom socket owner');
