use strict;
use warnings;
use File::Temp qw(tempdir);
use Test::More tests => 4;

my $dir = tempdir(CLEANUP => 1);
my (@handles, %handles);

ok(opendir($handles[0], $dir), 'opendir succeeds with an array element lvalue');
is(ref $handles[0], 'GLOB', 'opendir stores the glob reference in an array element');
ok(opendir($handles{entry}, $dir), 'opendir succeeds with a hash element lvalue');
is(ref $handles{entry}, 'GLOB', 'opendir stores the glob reference in a hash element');

closedir $handles[0];
closedir $handles{entry};
