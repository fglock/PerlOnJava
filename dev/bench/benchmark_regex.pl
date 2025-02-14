use strict;
use warnings;
use Benchmark;

my $count = 0;

sub loop_with_regex {
    my $i = 0;
    while ( $i < 400 ) {
        $count = $count + 1 if $i =~ /42/;
        $i = $i + 1;
    }
}

# Use timethis to benchmark the loop_with_regex subroutine
timethis(100000, sub { loop_with_regex() });

print "done\n";
