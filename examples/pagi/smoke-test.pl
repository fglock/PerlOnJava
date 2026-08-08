#!/usr/bin/env perl
use strict;
use warnings;

use FindBin;
use Future;

my $app = do "$FindBin::Bin/app.pl"
    or die "Unable to load $FindBin::Bin/app.pl: " . ($@ || $!) . "\n";

my @events;
my $receive = sub {
    return Future->done({
        type => 'http.request',
        body => '',
        more => 0,
    });
};
my $send = sub {
    push @events, $_[0];
    return Future->done;
};

$app->({ type => 'http', path => '/' }, $receive, $send)->get;

die "Expected response start and body events\n" unless @events == 2;
die "Expected HTTP 200\n" unless $events[0]{status} == 200;
die "Unexpected response body\n"
    unless $events[1]{body} eq "Hello from PAGI on PerlOnJava!\n";

print "PAGI application smoke test passed\n";
