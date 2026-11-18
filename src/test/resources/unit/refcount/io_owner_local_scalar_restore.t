use strict;
use warnings;
use Socket qw(AF_UNIX PF_UNSPEC SOCK_STREAM);
use IO::Select;
use Test::More tests => 11;

our $localized_io_owner;
my $peer;

{
    socketpair(my $writer, $peer, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "socketpair: $!";
    {
        local $localized_io_owner = $writer;
        is(syswrite($localized_io_owner, 'x'), 1,
            'localized socket is usable while the local value is installed');
    }
}

my $peer_readable = IO::Select->new($peer);
my @readable = $peer_readable->can_read(1);
is(scalar @readable, 1,
    'the peer receives the byte written through the localized socket');
my $buffer = '';
is(sysread($peer, $buffer, 1), 1, 'the peer reads the localized socket data');
@readable = $peer_readable->can_read(1);
is(scalar @readable, 1,
    'restoring the localized scalar releases its socket holder');
my $eof = @readable ? sysread($peer, $buffer, 1) : undef;
is($eof, 0,
    'the peer observes EOF after the last localized writer owner leaves');

my $exception_peer;
{
    socketpair(my $exception_writer, $exception_peer,
            AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or die "exception socketpair: $!";
    eval {
        local $localized_io_owner = $exception_writer;
        is(syswrite($localized_io_owner, 'y'), 1,
            'localized socket is usable before exception unwind');
        die "localized IO unwind\n";
    };
    is($@, "localized IO unwind\n", 'the localized scope unwinds the exception');
}

my $exception_readable = IO::Select->new($exception_peer);
@readable = $exception_readable->can_read(1);
is(scalar @readable, 1,
    'the exception peer receives data from the localized socket');
$buffer = '';
is(sysread($exception_peer, $buffer, 1), 1,
    'the exception peer reads the localized socket data');
@readable = $exception_readable->can_read(1);
is(scalar @readable, 1,
    'exception unwind releases the localized scalar socket holder');
my $exception_eof = @readable ? sysread($exception_peer, $buffer, 1) : undef;
is($exception_eof, 0,
    'the exception peer observes EOF after its final writer owner leaves');
