use strict;
use warnings;
use Test::More;

setgrent();
my @first_group = getgrent();
endgrent();
plan skip_all => 'system group database has no entries' unless @first_group >= 4;

my $name = $first_group[0];
my $gid = $first_group[2];
my @by_name = getgrnam($name);
my @by_gid = getgrgid($gid);

is_deeply(\@by_name, \@first_group, 'getgrnam returns the complete group record');
is_deeply(\@by_gid, \@first_group, 'getgrgid returns the complete group record');

setgrent();
my $scalar_name = scalar getgrent();
endgrent();
setgrent();
my @list_record = getgrent();
endgrent();
is($scalar_name, $list_record[0], 'scalar getgrent returns the group name');

done_testing;
