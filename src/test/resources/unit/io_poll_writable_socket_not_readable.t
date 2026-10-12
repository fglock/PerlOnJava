use strict;
use warnings;
use Test::More;
use IO::Poll qw(POLLIN POLLOUT);
use IO::Socket::UNIX;
use Socket qw(PF_UNIX SOCK_STREAM);

my ($read_socket, $write_socket) = IO::Socket::UNIX->socketpair(
    PF_UNIX, SOCK_STREAM, 0,
) or plan skip_all => "socketpair unavailable: $!";
binmode($write_socket, ':utf8') or die "binmode socket: $!";

sub check_registration_order {
    my ($read_first) = @_;
    my $fd = fileno($write_socket);
    my ($read_events, $write_events) = (POLLIN, POLLOUT);
    my @poll = $read_first
        ? ($fd, $read_events, $fd, $write_events)
        : ($fd, $write_events, $fd, $read_events);
    my $ready = IO::Poll::_poll(0, @poll);
    cmp_ok($ready, '>=', 1, 'idle socket reports its writable registration');
    my $input_result = $read_first ? $poll[1] : $poll[3];
    my $output_result = $read_first ? $poll[3] : $poll[1];
    ok($output_result & POLLOUT, 'writable registration reports POLLOUT');
    ok(!($input_result & POLLIN),
        'writable socket does not complete its separate POLLIN registration');
}

check_registration_order(1);
check_registration_order(0);

done_testing;
