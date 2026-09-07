use strict;
use warnings;
use Socket qw(AF_UNIX SOCK_STREAM PF_UNSPEC);
use Test::More;

socketpair(my $writer, my $reader, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
    or plan skip_all => "socketpair unavailable: $!";

is(syswrite($writer, 'response'), 8, 'writer sends response bytes');
ok(shutdown($writer, 1), 'shutdown closes only the writer send side');
is(sysread($reader, my $response, 8), 8, 'reader receives response bytes');
is($response, 'response', 'reader receives the complete response');
is(sysread($reader, my $eof, 1), 0,
    'reader promptly observes EOF after peer shuts down its send side');

done_testing;
