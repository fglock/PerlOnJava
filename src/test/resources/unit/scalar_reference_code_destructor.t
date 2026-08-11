use strict;
use warnings;

use Test::More tests => 2;

my $destroyed;

sub Local::Guard::DESTROY {
    ${$_[0]}->();
}

sub make_guard (&) {
    my $callback = shift;
    return bless \$callback, 'Local::Guard';
}

my %tree;
%tree = (
    nested => {
        guard => make_guard(sub { $destroyed++; delete $tree{nested}; }),
    },
);

ok(!defined($destroyed), 'blessed CODE scalar reference remains alive in its container');
delete $tree{nested}{guard};
is($destroyed, 1, 'deleting the final scalar reference invokes DESTROY');
