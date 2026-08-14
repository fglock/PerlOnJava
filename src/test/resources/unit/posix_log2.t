use strict;
use warnings;
use Test::More tests => 4;

use POSIX qw(log2);

ok(defined(&log2), 'POSIX exports log2 on request');
{
    package DefaultImport;
    POSIX->import();
    sub has_log2 { defined(&log2) }
}
ok(!DefaultImport::has_log2(), 'POSIX does not export log2 by default');
is(log2(8), 3, 'log2 computes an integral power of two');
cmp_ok(abs(log2(10) - (log(10) / log(2))), '<', 1e-12,
    'log2 agrees with the logarithm identity');
