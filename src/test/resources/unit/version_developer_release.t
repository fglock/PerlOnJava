use strict;
use warnings;
use Test::More;

{
    package VersionDeveloperRelease;
    our $VERSION = '1.00_01';
}

my $ok = eval { VersionDeveloperRelease->VERSION('0.59'); 1 };
ok($ok, 'a developer release version satisfies an older module requirement')
    or diag $@;

done_testing;
