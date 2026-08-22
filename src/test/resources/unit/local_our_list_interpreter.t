use strict;
use warnings;
use Test::More;

our ($topdir, $topdev, $topino, $topmode, $topnlink) = ('outer', 1, 2, 3, 4);
our $direct = 'outer direct';
our $plain_list = 'outer plain list';

{
    local our $direct;
    is($direct, undef, 'single local our remains a supported control');
    $direct = 'inner direct';
}
is($direct, 'outer direct', 'single local our restores its package scalar');

{
    local ($plain_list);
    is($plain_list, undef, 'plain local list remains a supported control');
    $plain_list = 'inner plain list';
}
is($plain_list, 'outer plain list', 'plain local list restores its package scalar');

{
    local our ($topdir, $topdev, $topino, $topmode, $topnlink);

    is($topdir, undef, 'local our list resets the package scalar');
    $topdir = 'inner';
    $topdev = 10;
    is($topdir, 'inner', 'localized package scalar is assignable');
    is($topdev, 10, 'each localized package scalar remains distinct');
}

is($topdir, 'outer', 'package scalar is restored after local scope');
is($topdev, 1, 'second package scalar is restored after local scope');

done_testing;
