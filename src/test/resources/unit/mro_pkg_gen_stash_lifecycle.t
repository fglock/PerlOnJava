use strict;
use warnings;
use Test::More;
use mro;

ok(!mro::get_pkg_gen('MroPkgGenLifecycle::Missing'),
    'a package with no stash has generation zero');

{
    package MroPkgGenLifecycleExisting;
    our @ISA = ();
}

ok(mro::get_pkg_gen('MroPkgGenLifecycleExisting') > 0,
    'a package with a stash has a positive generation');
undef %MroPkgGenLifecycleExisting::;
is(mro::get_pkg_gen('MroPkgGenLifecycleExisting'), 1,
    'clearing a stash resets its generation');
delete $::{'MroPkgGenLifecycleExisting::'};
is(mro::get_pkg_gen('MroPkgGenLifecycleExisting'), 0,
    'deleting a stash resets its generation to zero');

done_testing;
