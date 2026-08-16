use strict;
use warnings;
use Test::More tests => 3;
use IO::File;

my $pipe = IO::File->new;
my $pid = $pipe->open('-|');

if (!defined $pid) {
    fail('fork-open creates a pipe child');
    fail('parent reads the pipe child output');
    fail('wait returns the pipe child pid');
}
elsif (!$pid) {
    print "pipe child output\n";
    exit 0;
}
else {
    ok($pid > 0, 'fork-open creates a pipe child');
    is(do { local $/; <$pipe> }, "pipe child output\n",
        'parent reads the pipe child output');
    is(wait, $pid, 'wait returns the pipe child pid');
}
