use strict;
use warnings;
use Test::More tests => 3;

my ($opened, $error);
my $survived = eval {
    open(my $fh, '>&THIS_HANDLE_DOES_NOT_EXIST');
    $opened = defined(fileno($fh));
    $error = "$!";
    1;
};
ok($survived, 'missing duplicate source does not throw');
ok(!$opened, 'missing duplicate source does not open destination');
like($error, qr/(?:Bad file descriptor|Invalid argument)/i, 'missing duplicate source sets an OS error');
