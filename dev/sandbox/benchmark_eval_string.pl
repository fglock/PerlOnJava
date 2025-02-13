use strict;
use warnings;
use Benchmark;

my $count = 0;

sub loop_with_eval {
    print "call\n";
    my $k = 0;
    eval '
                while ( $k < 400 ) {
                    $k = $k + 1;
                    $count = $count + 1;
                }
    ';
}

# Use timethis to benchmark the loop_with_eval subroutine
timethis(10, sub {loop_with_eval()});

print "done $count\n";