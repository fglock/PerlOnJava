use strict;
use warnings;

print "1..2\n";

my @mapped_source = (1, 2);
my @mapped = map {
    unshift @mapped_source, 9;
    $_;
} @mapped_source;
print join(',', @mapped) eq '1,2' ? "ok 1\n" : "not ok 1\n";

my @grep_source = (1, 2);
my @filtered = grep {
    unshift @grep_source, 9;
    1;
} @grep_source;
print join(',', @filtered) eq '1,2' ? "ok 2\n" : "not ok 2\n";
