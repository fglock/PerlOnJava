#!/usr/bin/env perl
use strict;
use warnings;

use FindBin;
use IO::Async::Loop;
use PAGI::Server;

my $host = $ENV{PAGI_HOST} // '127.0.0.1';
my $port = $ENV{PAGI_PORT} // 5000;
my $app  = do "$FindBin::Bin/app.pl"
    or die "Unable to load $FindBin::Bin/app.pl: " . ($@ || $!) . "\n";

my $loop = IO::Async::Loop->new;
my $server = PAGI::Server->new(
    app           => $app,
    host          => $host,
    port          => $port,
    lifespan_mode => 'off',
);

$loop->add($server);
$server->listen->get;
print "PAGI example listening on http://$host:$port/\n";
$loop->run;
