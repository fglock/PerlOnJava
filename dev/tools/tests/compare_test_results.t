use strict;
use warnings;

use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools', 'compare_test_results.pl');
my $temporary = tempdir(CLEANUP => 1);
my $baseline = File::Spec->catfile($temporary, 'baseline.log');
my $candidate = File::Spec->catfile($temporary, 'candidate.json');
my $report = File::Spec->catfile($temporary, 'comparison.json');

write_file($baseline, <<'BASELINE');
[  1/2] perl5_t/t/re/a.t ... x 2/4 ok (1.00s)
[  2/2] perl5_t/t/re/b.t ... x 3/3 ok (1.00s)
BASELINE
write_file($candidate, JSON::PP->new->pretty->encode({
    results => {
        'perl5_t/t/re/a.t' => { ok_count => 4, total_tests => 4, status => 'pass' },
        'perl5_t/t/re/b.t' => { ok_count => 2, total_tests => 3, status => 'fail' },
    },
}));

my @command = ($^X, $tool, '--fail-on-regression',
    '--path-prefix', 'perl5_t/t/re', '--output', $report,
    $baseline, $candidate);
my $output = qx{@command 2>&1};
is($? >> 8, 1, 'regression mode exits nonzero');
like($output, qr/Passing assertions: 5\/7 -> 6\/7 \(\+1 passing, \+0 planned\)/,
    'aggregate delta is explicit');
like($output, qr/REGRESSIONS.*b\.t: 3\/3 -> 2\/3 \(-1\)/s,
    'per-file regression is reported');
like($output, qr/IMPROVEMENTS.*a\.t: 2\/4 -> 4\/4 \(\+2\)/s,
    'per-file improvement is reported');

open my $fh, '<:raw', $report or die "cannot read $report: $!";
my $document = JSON::PP->new->utf8->decode(do { local $/; <$fh> });
close $fh;
is($document->{summary}{delta_ok}, 1, 'JSON report preserves aggregate delta');
is(scalar @{$document->{regressions}}, 1, 'JSON report preserves regressions');

done_testing;

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>:raw', $path or die "cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "cannot close $path: $!";
}
