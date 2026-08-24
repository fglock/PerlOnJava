use strict;
use Test::More tests => 2;

my ($warning, $count);
{
    local $_ = 'adam';
    local $SIG{__WARN__} = sub { $warning = shift; ++$count };
    local $^W = 1;
    eval 'y///r; 1';
}
like($warning, qr/^Useless use of non-destructive transliteration \(tr\/\/\/r\)/,
    'dynamic warnings enable the tr///r void warning');
is($count, 1, 'dynamic tr///r void warning is emitted once');
