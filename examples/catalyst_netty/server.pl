use strict;
use warnings;
use FindBin;
use lib "$FindBin::Bin/lib";
use CatalystNetty;
use Plack::Handler::Netty;

my $port = $ENV{CATALYST_NETTY_PORT} || 5099;
my $handler = Plack::Handler::Netty->new(
    host => '127.0.0.1',
    port => $port,
);

$handler->run(CatalystNetty->psgi_app);
