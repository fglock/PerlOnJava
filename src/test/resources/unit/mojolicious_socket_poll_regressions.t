use strict;
use warnings;
use Test::More;
use IO::Poll qw(POLLIN POLLOUT);
use IO::Socket::INET;

my $listener = IO::Socket::INET->new(
    Listen    => 5,
    LocalAddr => '127.0.0.1',
    LocalPort => 0,
    Proto     => 'tcp',
    ReuseAddr => 1,
) or die "listener: $!";

my $poll = IO::Poll->new;
$poll->mask($listener, POLLIN | POLLOUT);

is $poll->poll(0.05), 0,
    'listener watched for read and write starts without readiness';
is $poll->events($listener) || 0, 0,
    'listener does not report writable readiness';

my $client = IO::Socket::INET->new(
    PeerAddr => '127.0.0.1',
    PeerPort => $listener->sockport,
    Proto    => 'tcp',
) or die "client: $!";

cmp_ok $poll->poll(1), '>=', 1,
    'listener becomes readable when a connection is queued';
ok $poll->events($listener) & POLLIN,
    'listener reports the queued connection as readable';
ok !($poll->events($listener) & POLLOUT),
    'listener still does not report writable readiness';

my $accepted = $listener->accept or die "accept: $!";
is $poll->poll(0.05), 0,
    'listener readiness clears after accepting the connection';

done_testing;
