use strict;
use warnings;

use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More tests => 5;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools',
    'compare_test_results.pl');
my $temporary = tempdir(CLEANUP => 1);
my $baseline = File::Spec->catfile($temporary, 'baseline.json');
my $candidate = File::Spec->catfile($temporary, 'candidate.json');
my $file_list = File::Spec->catfile($temporary, 'regex-files.txt');

write_file($baseline, results_json(
    'perl5_t/t/re/a.t' => [2, 2],
    'perl5_t/t/re/ab.t' => [3, 3],
    'perl5_t/t/op/plain.t' => [4, 4],
));
write_file($candidate, results_json(
    'perl5_t/t/re/a.t' => [1, 2],
    'perl5_t/t/re/ab.t' => [3, 3],
));
write_file($file_list, "# generated ledger\n./perl5_t/t/re/a.t\n\n");

my @command = ($^X, $tool, '--file-list', $file_list,
    $baseline, $candidate);
my $output = qx{@command 2>&1};
is($? >> 8, 0, 'exact file-list comparison succeeds');
like($output, qr/Files: 1 -> 1/,
    'only the listed identity is compared');
like($output, qr/a\.t: 2\/2 -> 1\/2/,
    'listed regression is retained');
unlike($output, qr/ab\.t|plain\.t/,
    'unlisted neighboring and unrelated files are excluded');

my $conflict = qx{$^X $tool --file-list $file_list --path-prefix perl5_t/t/re $baseline $candidate 2>&1};
isnt($? >> 8, 0, 'conflicting filters are rejected');

sub results_json {
    my (%counts) = @_;
    my %results = map {
        $_ => {
            ok_count => $counts{$_}[0],
            total_tests => $counts{$_}[1],
            status => $counts{$_}[0] == $counts{$_}[1] ? 'pass' : 'partial',
        }
    } keys %counts;
    return JSON::PP->new->canonical->encode({ results => \%results });
}

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!\n";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!\n";
}
