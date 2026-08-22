#!/usr/bin/env perl

use strict;
use warnings;

use Digest::SHA qw(sha256_hex);
use Time::HiRes qw(time);

# This fixture intentionally uses only ordinary, constant regexes.  It is an
# executable semantic checksum first and a microbenchmark second: every run
# performs the same ordered work and must produce the same digest.
my @subjects = (
    'alpha-001:ordinary',
    'beta-020:backtracking',
    'gamma-300:captures',
    'delta-004:substitution',
    'epsilon-050:lookahead',
    'zeta-600:boundaries',
    'eta-007:iteration',
    'theta-080:checksum',
);
my $rounds = 1_500;
my ($score, $operations) = (0, 0);
my $started = time();

for my $round (1 .. $rounds) {
    for my $subject (@subjects) {
        if ($subject =~ /\A([a-z]+)-(\d{3}):([a-z]+)\z/) {
            $score += length($1) * 3 + 0 + $2 + length($3) * 5 + $round % 17;
        } else {
            die "ordinary capture contract failed\n";
        }
        $operations++;

        my $vowels = 0;
        while ($subject =~ /([aeiou])(?=[a-z])/g) {
            $vowels += ord($1);
        }
        $score += $vowels;
        $operations++;

        my $copy = $subject;
        $copy =~ s/\A([a-z])/\U$1/;
        $copy =~ s/-\d{3}:/-999:/;
        $score += length($copy) + ord(substr($copy, 0, 1));
        $operations += 2;

        $score += 97 if $subject =~ /(?:ordinary|captures|lookahead)/;
        $score += 101 if $subject !~ /[^\x00-\x7f]/;
        $operations += 2;
    }
}

my $checksum = sha256_hex(join(':', $score, $operations, $rounds,
    scalar(@subjects)));
my $expected = '135a355df10cd13cd6bb7eb074e4aaf326b61057ab83753423033a50da258458';
die "ordinary regex semantic checksum changed: $checksum\n"
    unless $checksum eq $expected;

my $elapsed = time() - $started;
$elapsed = 0.000_000_001 if $elapsed <= 0;
my $throughput = $operations / $elapsed;
my $source = $ENV{PHASE36_SOURCE_COMMIT} // ('0' x 40);
my $jar = $ENV{PHASE36_JAR_SHA256} // ('0' x 64);
die "PHASE36_SOURCE_COMMIT is malformed\n"
    unless $source =~ /\A[0-9a-f]{40}\z/;
die "PHASE36_JAR_SHA256 is malformed\n"
    unless $jar =~ /\A[0-9a-f]{64}\z/;

printf "PHASE36_REGEX_PERFORMANCE elapsed_seconds=%.9f throughput=%.3f checksum=%s jar_sha256=%s source_commit=%s\n",
    $elapsed, $throughput, $checksum, $jar, $source;
