use strict;
use warnings;
use Test::More tests => 2;

package Issue1713;
our $target = 'outer';
package main;
my $name = 'Issue1713::target';
{
    no strict 'refs';
    local $$name;
    ok(!defined $Issue1713::target,
        'local $$name without an assignment undefines the named scalar');
    $Issue1713::target = 'inner';
}

is($Issue1713::target, 'outer',
    'local $$name restores the named scalar after scope exit');
