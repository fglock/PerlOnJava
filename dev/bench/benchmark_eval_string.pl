use strict;
use warnings;
use Benchmark;

my $count = 0;

my $k = 0;
sub loop_with_eval {
    $k++;
    my $str = ( '
                while ( $k < 400 ) {
                    $k = $k + 1;
                    $count = $count + 1;
                }
    ' . "# " . $k );  # add a difference to avoid caching
    eval $str;
}

# Use timethis to benchmark the loop_with_eval subroutine
timethis(100000, sub {loop_with_eval()});

print "done\n";
