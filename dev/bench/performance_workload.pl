#!/usr/bin/env perl

# Emits deterministic, per-window measurements for one portfolio workload.
# It intentionally contains no engine-selection logic; run_performance_portfolio.pl
# owns fresh-process ordering and evidence collection.
use strict;
use warnings;
use Getopt::Long qw(GetOptions);
use JSON::PP;
use Time::HiRes qw(time);

my %option = (window_seconds => 1, windows => 15, warmup_min => 0, warmup_max => 0);
GetOptions(
    'workload=s'       => \$option{workload},
    'window-seconds=i' => \$option{window_seconds},
    'windows=i'        => \$option{windows},
    'warmup-min=i'     => \$option{warmup_min},
    'warmup-max=i'     => \$option{warmup_max},
) or die "invalid options\n";
die "--workload is required\n" unless defined $option{workload};
die "window length must be positive\n" unless $option{window_seconds} > 0;
die "window count must be positive\n" unless $option{windows} > 0;
die "warmup maximum must be at least warmup minimum\n"
    if $option{warmup_max} < $option{warmup_min};

my ($operation, $operations_per_iteration, $checksum) = workload($option{workload});
$checksum = $operation->() unless defined $checksum;
my @warmup;
for my $window (1 .. $option{warmup_max}) {
    push @warmup, run_window($operation, $operations_per_iteration,
        $option{window_seconds}, $window);
    last if warmup_stabilized(\@warmup) && $window >= $option{warmup_min};
}
my @windows = map { run_window($operation, $operations_per_iteration,
    $option{window_seconds}, $_) } 1 .. $option{windows};

print JSON::PP->new->canonical->encode({
    schema_version => 1,
    kind => 'perlonjava-performance-workload',
    workload => $option{workload},
    warmup_stabilized => warmup_stabilized(\@warmup),
    semantic_checksum => "$checksum",
    operations_per_iteration => $operations_per_iteration,
    warmup_windows => \@warmup,
    windows => \@windows,
}), "\n";

sub run_window {
    my ($operation, $operations_per_iteration, $seconds, $window) = @_;
    my ($iterations, $value) = (0, 0);
    my $started = time;
    do {
        my $result = $operation->();
        die "workload semantic checksum changed\n" if $result != $checksum;
        $value ^= $result;
        ++$iterations;
    } while (time - $started < $seconds);
    my $elapsed = time - $started;
    return {
        index => $window,
        elapsed_seconds => 0 + $elapsed,
        iterations => $iterations,
        operations => $iterations * $operations_per_iteration,
        throughput => ($iterations * $operations_per_iteration) / $elapsed,
        rolling_value => 0 + $value,
    };
}

sub warmup_stabilized {
    my ($samples) = @_;
    return JSON::PP::false if @$samples < 5;
    my @rates = map { $_->{throughput} } @$samples[-5 .. -1];
    my $mean = sum(\@rates) / @rates;
    my $cv = sqrt(sum([map { ($_ - $mean) ** 2 } @rates]) / @rates) / $mean;
    my $slope = abs($rates[-1] - $rates[0]) / $mean;
    return ($cv < .03 && $slope < .02) ? JSON::PP::true : JSON::PP::false;
}

sub sum { my ($values) = @_; my $sum = 0; $sum += $_ for @$values; return $sum }

sub workload {
    my ($name) = @_;
    if ($name eq 'closure') {
        my ($a, $b, $c) = (1, 2, 3);
        my $make = sub { my ($x, $y, $z) = @_; my ($u, $v, $w) = ($x + 1, $y + 2, $z + 3); return sub { $u + $v + $w + $a + $b + $c } };
        my $f = $make->(10, 20, 30);
        return (sub { my $sum = 0; $sum += $f->() for 1 .. 128; return $sum }, 128, undef);
    }
    if ($name eq 'method') {
        my $class = 'PortfolioMethod';
        no strict 'refs'; ## no critic
        *{"${class}::new"} = sub { bless { x => 1, y => 2 }, shift };
        *{"${class}::add"} = sub { my ($self, $n) = @_; $self->{x} += $n; $self->{y} += $n; return $self->{x} + $self->{y} };
        return (sub { my $o = $class->new; my $sum = 0; $sum += $o->add(1) for 1 .. 64; return $sum }, 64, 4352);
    }
    if ($name eq 'numeric') {
        our $global;
        return (sub { $global = 7; my $lexical = 11; for (1 .. 2048) { $lexical = ($lexical * 33 + $_) % 1_000_003; $global = ($global + $lexical) % 1_000_003 } return $lexical ^ $global }, 2048, undef);
    }
    if ($name eq 'string') {
        return (sub { my $s = 'PerlOnJava'; for (1 .. 256) { $s = substr($s . ':' . $_, -24) } return length($s) }, 256, 24);
    }
    if ($name eq 'regex') {
        my $text = join ':', qw(alpha beta 42 gamma delta 42 epsilon zeta);
        return (sub { my $count = 0; for (1 .. 256) { pos($text) = 0; ++$count while $text =~ /(?:42|gamma|epsilon)/g } return $count }, 768, undef);
    }
    if ($name eq 'json') {
        my $json = JSON::PP->new->canonical;
        my $input = { alpha => [1, 2, 3], beta => { enabled => JSON::PP::true, text => 'PerlOnJava' } };
        return (sub { my $text = $json->encode($input); my $out = $json->decode($text); return scalar @{$out->{alpha}} + length($out->{beta}{text}) }, 2, 13);
    }
    if ($name eq 'life') {
        # A fixed flat word-level kernel.  The full application's parallel and
        # flat layouts remain companion diagnostics; this kernel is
        # deterministic and window-friendly.
        my @seed = map { (($_ * 2_654_435_761) ^ 0x5a5a5a5a) & 0xffff_ffff } 1 .. 128;
        return (sub { my @grid = @seed; for (1 .. 16) { my @next; for my $i (0 .. $#grid) { my $left = $grid[($i - 1) % @grid]; my $cell = $grid[$i]; my $right = $grid[($i + 1) % @grid]; $next[$i] = ((($cell << 1) | ($left >> 31)) ^ (($cell >> 1) | (($right & 1) << 31)) ^ ($left & $right)) & 0xffff_ffff } @grid = @next } my $sum = 0; $sum ^= $_ for @grid; return $sum }, 2048, undef);
    }
    die "unknown workload '$name' (expected closure, method, numeric, string, regex, life, or json)\n";
}
