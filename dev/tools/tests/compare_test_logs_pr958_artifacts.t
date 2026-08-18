use strict;
use warnings;

use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools', 'compare_test_logs.pl');
my $temporary = tempdir(CLEANUP => 1);
my $baseline = File::Spec->catfile($temporary, 'baseline.log');
my $candidate = File::Spec->catfile($temporary, 'candidate.log');

write_file($baseline, <<'BASELINE');
[  1/3] perl5_t/t/op/do.t ... x 94/99 ok (1.00s)
[  2/3] perl5_t/t/japh/abigail.t ... x 110/130 ok (1.00s)
[  3/3] perl5_t/t/op/real.t ... x 7/10 ok (1.00s)
BASELINE
write_file($candidate, <<'CANDIDATE');
[  1/3] perl5_t/t/op/do.t ... x 69/71 ok (1.00s)
[  2/3] perl5_t/t/japh/abigail.t ... x 109/130 ok (1.00s)
[  3/3] perl5_t/t/op/real.t ... x 6/10 ok (1.00s)
CANDIDATE

my $raw = run_tool($baseline, $candidate);
like($raw, qr/op\/do\.t\s+94\/99\s+69\/71\s+-25/,
    'default report retains raw transcript counts');
like($raw, qr/japh\/abigail\.t\s+110\/130\s+109\/130\s+-1/,
    'default report does not silently normalize artifacts');

my $normalized = run_tool('--normalize-pr958-artifacts', $baseline, $candidate);
like($normalized, qr/old op\/do\.t\s+94\/99 -> 68\/71/,
    'exact duplicated-TAP signature reports raw and normalized counts');
like($normalized, qr/old japh\/abigail\.t\s+110\/130 -> 109\/130/,
    'exact reconstructed-count signature reports raw and normalized counts');
like($normalized, qr/op\/do\.t\s+68\/71\s+69\/71\s+\+1/,
    'normalized report preserves the canonical do.t improvement');
unlike($normalized, qr/japh\/abigail\.t\s+\d+\/\d+\s+\d+\/\d+\s+[+-]\d/,
    'reconstructed abigail counts compare unchanged');
like($normalized, qr/op\/real\.t\s+7\/10\s+6\/10\s+-1/,
    'unlisted regression remains visible');

done_testing;

sub run_tool {
    my @args = @_;
    open my $fh, '-|', $^X, $tool, @args
        or die "cannot run $tool: $!";
    my $output = do { local $/; <$fh> };
    close $fh or die "$tool failed: $?";
    return $output;
}

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>:raw', $path or die "cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "cannot close $path: $!";
}
