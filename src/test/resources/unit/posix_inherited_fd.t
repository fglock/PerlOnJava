use strict;
use warnings;
use Test::More;
use IO::Socket::INET;
use POSIX ();

plan skip_all => 'descriptor remapping is Unix-specific' if $^O eq 'MSWin32';

# Occupy the activation descriptor before creating the listening socket.  This
# exercises systemd's save/dup2/fdopen/restore sequence even when this test is
# run without a harness that keeps an internal pipe at fd 3.
my $fixture = 'src/test/resources/unit/posix_inherited_fd.t';
open my $guard, '<', $fixture or die $!;
my $target_fd = fileno($guard);
my $socket = IO::Socket::INET->new(
    LocalAddr => '127.0.0.1',
    LocalPort => 0,
    Proto     => 'tcp',
    Listen    => 8,
    ReuseAddr => 1,
) or die "socket: $!";
my $socket_fd = fileno($socket);

isnt($socket_fd, $target_fd, 'socket is not already at activation fd');

my $saved_fd = POSIX::dup($target_fd);
ok(defined $saved_fd, 'saved the existing activation descriptor');
is(POSIX::dup2($socket_fd, $target_fd), $target_fd,
    'mapped listening socket to activation descriptor');

my $inherited;
ok(open($inherited, '+<&=', $target_fd),
    'fdopen borrowed the inherited descriptor');
is(getsockname($inherited), getsockname($socket),
    'inherited handle identifies the listening socket');

ok(POSIX::close($target_fd), 'closed activation descriptor');
is(POSIX::dup2($saved_fd, $target_fd), $target_fd,
    'restored the saved descriptor');
ok(POSIX::close($saved_fd), 'closed saved descriptor');

my $restored;
ok(open($restored, '<&=', $target_fd), 'opened restored descriptor');
my $first_line = <$restored>;
like($first_line, qr/^use strict;/, 'restored descriptor reads original stream');

done_testing;
