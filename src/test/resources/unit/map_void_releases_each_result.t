use strict;
use warnings;

print "1..2\n";
{
    package MapVoidReleasesEachResult;
    our $destroyed = 0;
    sub DESTROY { $destroyed++ }
}

my @destroyed_before_iteration;
map {
    push @destroyed_before_iteration, $MapVoidReleasesEachResult::destroyed;
    bless {}, 'MapVoidReleasesEachResult';
} 1, 2, 3;

print(join(' ', @destroyed_before_iteration) eq '0 1 2'
    ? "ok 1 - each void map result is released before the next block\n"
    : "not ok 1 - each void map result is released before the next block\n");
print($MapVoidReleasesEachResult::destroyed == 3
    ? "ok 2 - final void map result is released after the map\n"
    : "not ok 2 - final void map result is released after the map\n");
