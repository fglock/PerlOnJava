use strict;
use warnings;
use Test::More tests => 4;

'prior' =~ /(prior)/;
is($^N, 'prior', 'capture-bearing match publishes the last closed capture');

ok('plain' =~ /plain/, 'capture-free match succeeds');
ok(!defined $^N, 'capture-free match clears the last closed capture');

ok(!('miss' =~ /plain/), 'failed capture-free match does not publish a capture');
