use strict;
use warnings;
use Time::HiRes qw(time);

my $trials  = 10;
my $n       = 200_000;

my $count = 0;
my $k     = 0;

sub run_uncached {
    my @times;
    for my $t (1 .. $trials) {
        $k = 0;
        $count = 0;
        my $start = time();
        for (1 .. $n) {
            $k++;
            my $str = '
                while ( $k < 40 ) {
                    $k = $k + 1;
                    $count = $count + 1;
                }
            ' . "# " . $k;
            eval $str;
        }
        push @times, time() - $start;
    }
    return @times;
}

sub run_cached {
    my @times;
    my $str = 'my $x = 1';
    for my $t (1 .. $trials) {
        my $start = time();
        for (1 .. $n) {
            eval $str;
        }
        push @times, time() - $start;
    }
    return @times;
}

sub stats {
    my @vals = @_;
    my $sum  = 0; $sum += $_ for @vals;
    my $mean = $sum / @vals;
    my $ss   = 0; $ss += ($_ - $mean)**2 for @vals;
    my $sd   = sqrt($ss / (@vals - 1));
    my $se   = $sd / sqrt(scalar @vals);
    return ($mean, $sd, $se);
}

sub report {
    my ($label, $n_iter, @times) = @_;
    my ($mean, $sd, $se) = stats(@times);
    my $us = $mean / $n_iter * 1e6;
    my $us_se = $se / $n_iter * 1e6;
    printf "  %-10s %d trials x %d iters: %.3f +/- %.3f s  (%.2f +/- %.2f us/eval)\n",
        $label, scalar @times, $n_iter, $mean, $se, $us, $us_se;
    return ($us, $us_se);
}

print "=== eval STRING benchmark ($trials trials x $n iterations) ===\n\n";

print "Uncached (unique eval string each call):\n";
my @t = run_uncached();
my ($us, $us_se) = report("", $n, @t);

print "\nCached (same eval string each call):\n";
my @tc = run_cached();
my ($us_c, $us_c_se) = report("", $n, @tc);

my $engine = $ENV{JPERL_OPTS} ? "jperl" : ($^X =~ /jperl/ ? "jperl" : "perl");
printf "\nEngine: %s  (%s)\n", $engine, $^X;
printf "Uncached: %.2f +/- %.2f us/eval\n", $us, $us_se;
printf "Cached:   %.2f +/- %.2f us/eval\n", $us_c, $us_c_se;
