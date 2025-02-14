no strict;
use warnings;
use Benchmark;

$count = 0;

sub loop_with_global {
    $i = 0;
    while ( $i < 400 ) {
        $count++;
        $i++;
    }
}

# Use timethis to benchmark the loop_with_global subroutine
timethis(1000000, sub { loop_with_global() });

print "done\n";
