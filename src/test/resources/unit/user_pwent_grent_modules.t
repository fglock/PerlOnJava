use strict;
use warnings;
use Test::More;

use User::pwent;
use User::grent;

ok(User::pwent->can('getpwuid'), 'User::pwent loads and exports its interface');
ok(User::grent->can('getgrgid'), 'User::grent loads and exports its interface');

my $pw = getpwuid($<);
ok($pw, 'getpwuid returns a User::pwent object');
is($pw->uid, $<, 'User::pwent exposes the uid field');

my ($gid) = split /\s+/, $);
my $gr = getgrgid($gid);
ok($gr, 'getgrgid returns a User::grent object');
is($gr->gid, $gid, 'User::grent exposes the gid field');

done_testing;
