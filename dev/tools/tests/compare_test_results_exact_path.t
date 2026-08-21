use strict;
use warnings;

use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More tests => 3;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools',
    'compare_test_results.pl');
my $temporary = tempdir(CLEANUP => 1);
my $baseline = File::Spec->catfile($temporary, 'baseline.json');
my $candidate = File::Spec->catfile($temporary, 'candidate.json');
my $json = JSON::PP->new->canonical->encode({
    results => {
        'perl5_t/t/re/a.t' => {
            ok_count => 2, total_tests => 2, status => 'pass',
        },
        'perl5_t/t/re/ab.t' => {
            ok_count => 3, total_tests => 3, status => 'pass',
        },
    },
});
write_file($baseline, $json);
write_file($candidate, $json);

my @command = ($^X, $tool, '--path-prefix', 'perl5_t/t/re/a.t',
    $baseline, $candidate);
my $output = qx{@command 2>&1};
is($? >> 8, 0, 'one-file path prefix succeeds');
like($output, qr/Files: 1 -> 1/,
    'one-file path prefix selects exactly one result');
unlike($output, qr/ab\.t/,
    'neighboring filename is not selected by an exact test path');

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!\n";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!\n";
}
