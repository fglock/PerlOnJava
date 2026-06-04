use strict;
use warnings;
use Test::More;

sub package_exists {
    my $pkg = shift;
    no strict 'refs';
    if ($pkg =~ s/::([^:]+)\z//) {
        return !!${$pkg . '::'}{$1 . '::'};
    }
    return !!$::{$pkg . '::'};
}

package UnitPkgDeclOne;

package UnitPkgDeclTwo::UnitPkgDeclThree;

package UnitPkgDeclTwo::UnitPkgDeclThree::UnitPkgDeclFour;

package main;

ok(package_exists('UnitPkgDeclOne'), 'single package declaration creates top-level stash entry');
ok(package_exists('UnitPkgDeclTwo'), 'nested package declaration creates parent stash entry');
ok(package_exists('UnitPkgDeclTwo::UnitPkgDeclThree'), 'nested package declaration creates child stash entry');
is_deeply(
    [sort grep { /::$/ } keys %UnitPkgDeclTwo::],
    ['UnitPkgDeclThree::'],
    'parent stash lists declared child package'
);

done_testing;
