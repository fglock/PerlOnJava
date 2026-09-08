#!/usr/bin/env perl

# Summarize a portfolio evidence bundle without ever upgrading an
# inconclusive run into an authoritative performance claim.
use strict;
use warnings;
use Getopt::Long qw(GetOptions);
use JSON::PP;

my %option = (bootstrap => 10_000);
GetOptions('input=s' => \$option{input}, 'output=s' => \$option{output},
    'bootstrap=i' => \$option{bootstrap}, 'help' => \$option{help}) or usage(2);
usage(0) if $option{help};
die "--input is required\n" unless defined $option{input};
die "--bootstrap must be positive\n" unless $option{bootstrap} > 0;

my $portfolio = decode_file($option{input});
die "not a performance portfolio\n" unless ($portfolio->{kind} // '') eq 'perlonjava-performance-portfolio';
my @workloads;
for my $entry (@{$portfolio->{results} || []}) {
    my @ratios;
    for my $pair (@{$entry->{pairs} || []}) {
        my $perl = median([map { $_->{throughput} } @{$pair->{engines}{perl}{windows} || []}]);
        my $pj = median([map { $_->{throughput} } @{$pair->{engines}{perlonjava}{windows} || []}]);
        die "missing positive window throughput for $entry->{workload}\n" unless $perl > 0 && $pj > 0;
        push @ratios, $pj / $perl;
    }
    die "need at least two pairs for $entry->{workload}\n" unless @ratios >= 2;
    push @workloads, { workload => $entry->{workload}, pair_ratios => \@ratios,
        median_ratio => median(\@ratios), geometric_mean_ratio => geometric_mean(\@ratios),
        confidence_interval => bootstrap_ci(\@ratios, $option{bootstrap}) };
}
die "no workload results\n" unless @workloads;
my @all = map { @{$_->{pair_ratios}} } @workloads;
my @anchors = grep { $_->{workload} eq 'closure' || $_->{workload} eq 'life' } @workloads;
my $authority = ($portfolio->{protocol_compliant} && $portfolio->{conclusive}) ? JSON::PP::true : JSON::PP::false;
my $report = {
    schema_version => 1, kind => 'perlonjava-performance-portfolio-report',
    evidence => { input => $option{input}, generated_at_utc => $portfolio->{generated_at_utc},
        source_commit => $portfolio->{engines}{source_commit}, protocol_compliant => $portfolio->{protocol_compliant},
        conclusive => $portfolio->{conclusive} },
    authoritative => $authority, workloads => \@workloads,
    portfolio_geometric_mean_ratio => geometric_mean(\@all),
    portfolio_confidence_interval => bootstrap_ci(\@all, $option{bootstrap}),
    minimum_workload_ratio => (sort { $a <=> $b } map { $_->{median_ratio} } @workloads)[0],
    acceptance => acceptance($authority, \@workloads, \@anchors),
};
my $json = JSON::PP->new->canonical->pretty->encode($report);
if (defined $option{output}) { open my $fh, '>:raw', $option{output} or die "cannot write $option{output}: $!\n"; print {$fh} $json; close $fh or die "cannot close $option{output}: $!\n"; }
print $json;

sub acceptance {
    my ($authority, $workloads, $anchors) = @_;
    return { passed => JSON::PP::false, reason => 'input is protocol-inconclusive; not an authoritative baseline' } unless $authority;
    my $portfolio = geometric_mean([map { $_->{median_ratio} } @$workloads]);
    return { passed => JSON::PP::false, reason => 'portfolio geometric mean is below 1.05x Perl' } if $portfolio < 1.05;
    return { passed => JSON::PP::false, reason => 'a scored workload is below 0.90x Perl' }
        if grep { $_->{median_ratio} < .90 } @$workloads;
    return { passed => JSON::PP::false, reason => 'closure or Life anchor is below 1.05x Perl' }
        if @$anchors != 2 || grep { $_->{median_ratio} < 1.05 } @$anchors;
    return { passed => JSON::PP::true, reason => 'all performance gates passed' };
}
sub bootstrap_ci {
    my ($values, $count) = @_;
    srand(1196); my @samples;
    for (1 .. $count) { push @samples, geometric_mean([map { $values->[int rand @$values] } 1 .. @$values]); }
    @samples = sort { $a <=> $b } @samples;
    return { lower => $samples[int(.025 * $#samples)], upper => $samples[int(.975 * $#samples)] };
}
sub median { my ($v) = @_; my @v = sort { $a <=> $b } @$v; return $v[@v / 2] if @v % 2; return ($v[@v / 2 - 1] + $v[@v / 2]) / 2 }
sub geometric_mean { my ($v) = @_; my $sum = 0; $sum += log $_ for @$v; return exp($sum / @$v) }
sub decode_file { my ($path) = @_; open my $fh, '<:raw', $path or die "cannot read $path: $!\n"; local $/; return JSON::PP->new->decode(<$fh>) }
sub usage { my ($s) = @_; print "usage: $0 --input portfolio.json [--output report.json] [--bootstrap N]\n"; exit $s }
