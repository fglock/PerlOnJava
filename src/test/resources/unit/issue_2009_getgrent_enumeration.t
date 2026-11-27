use strict;
use warnings;
use Test::More;

plan skip_all => 'group database enumeration is unavailable on Windows'
    if $^O eq 'MSWin32';

my $gid = (stat '.')[5];
my @by_gid = getgrgid($gid);
plan skip_all => 'group lookup is unavailable on this platform' unless @by_gid;

setgrent();
my @entries;
while (@entries < 4096) {
    my @entry = getgrent();
    last unless @entry && defined($entry[0]) && length($entry[0]);
    push @entries, \@entry;
}
endgrent();

ok(@entries > 0, 'getgrent enumerates at least one group entry');
ok((grep { $_->[2] == $gid } @entries),
    'group enumeration includes the group returned by getgrgid');

done_testing;
