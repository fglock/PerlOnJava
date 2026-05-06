#!/usr/bin/env perl
use strict;
use warnings;
use FindBin;
use lib "$FindBin::Bin/../../../src/main/perl/lib";

# Phase 2 Test: Run Dancer2 app with Plack::Handler::Netty
#
# Usage:
#   ./jperl test_dancer.pl
# Or with plackup (if available):
#   plackup -s Netty -p 5000 dancer_app.pl

print "Loading Dancer2 app...\n";

# Load the Dancer2 app
require "$FindBin::Bin/dancer_app.pl";
my $app = main->to_app;

print "Loading Plack::Handler::Netty...\n";
require Plack::Handler::Netty;

print "\n";
print "=" x 60 . "\n";
print "  Dancer2 on Netty - Test Server\n";
print "  http://localhost:5000\n";
print "=" x 60 . "\n";
print "\n";
print "Test endpoints:\n";
print "  curl http://localhost:5000/\n";
print "  curl http://localhost:5000/user/123\n";
print "  curl http://localhost:5000/api/users\n";
print "  curl http://localhost:5000/search?q=test\n";
print "  curl -X POST http://localhost:5000/form -d 'name=Alice&age=30'\n";
print "\n";

my $handler = Plack::Handler::Netty->new(port => 5000);
$handler->run($app);
