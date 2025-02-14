use strict;
use warnings;
use Benchmark;

my $count = 0;

sub loop_with_string {
    my $i = 0;
    while ( $i < 400 ) {
        $count++;
        $i++;
    }
}

# Use timethis to benchmark the loop_with_lexical subroutine
timethis(1000000, sub { loop_with_string() });

print "done\n";
