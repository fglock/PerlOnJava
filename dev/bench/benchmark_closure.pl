use strict;
use warnings;
use Benchmark;

my ($a, $b, $c) = (1, 2, 3);

sub make_closure {
    my ($x, $y, $z) = @_;
    my $u = $x + 1;
    my $v = $y + 2;
    my $w = $z + 3;
    return sub {
        return $u + $v + $w + $a + $b + $c;
    };
}

my $f = make_closure(10, 20, 30);
my $sink = 0;

sub loop_call_closure {
    my $i = 0;
    while ($i < 20000) {
        $sink += $f->();
        $i++;
    }
}

timethis(5000, sub { $sink = 0; loop_call_closure() });
print "done $sink\n";
