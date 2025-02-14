use strict;
use warnings;
use Benchmark;

my $count = 0;
my $s = "a";

sub loop_with_string {
    my $i = 0;
    while ( $i < 400 ) {
        $s++;
        $count++;
        $i++;
    }
}

# Use timethis to benchmark the loop_with_string subroutine
timethis(100000, sub { loop_with_string() });

print "done $count $s\n";
