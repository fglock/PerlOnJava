use strict;
use warnings;

use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools',
    'generate_regex_test_ledger.pl');
my $temporary = tempdir(CLEANUP => 1);
my $tests = File::Spec->catdir($temporary, 'perl5_t', 't');
my $units = File::Spec->catdir($temporary, 'unit');
make_path(map { File::Spec->catdir($tests, $_) } qw(re op uni));
make_path($units);

write_file(File::Spec->catfile($tests, 're', 'a.t'), "ok 1; qr/a/;\n");
write_file(File::Spec->catfile($tests, 're', 'a_thr.t'), "ok 1;\n");
write_file(File::Spec->catfile($tests, 'op', 'match.t'), "'a' =~ /a/;\n");
write_file(File::Spec->catfile($tests, 'op', 'plain.t'), "ok 1;\n");
write_file(File::Spec->catfile($tests, 'uni', 'property.t'), "qr/\\p{L}/;\n");
write_file(File::Spec->catfile($units, 'focused.t'), "ok 1;\n");
my $reference = File::Spec->catfile($temporary, 'reference.md');
write_file($reference, "Gates: `focused.t`, `op/plain.t`, `missing.t`.\n");
my $json_path = File::Spec->catfile($temporary, 'ledger.json');
my $list_path = File::Spec->catfile($temporary, 'runner.list');

my @command = ($^X, $tool,
    '--tests-root', $tests,
    '--unit-root', $units,
    '--reference', $reference,
    '--runner-list', $list_path,
    '--output', $json_path);
system @command;
is($? >> 8, 0, 'ledger generation succeeds');

my $ledger = JSON::PP->new->decode(read_file($json_path));
is($ledger->{summary}{core_re_files}, 2, 'complete re directory is counted');
is($ledger->{summary}{auxiliary_regex_files}, 2,
    'regex-bearing op and uni files are discovered');
is($ledger->{summary}{runner_files}, 5,
    'documented auxiliary imported tests join the runner list');
is_deeply($ledger->{documented_unit_gates},
    [File::Spec->catfile($units, 'focused.t')],
    'documented unit gate is resolved separately');
is($ledger->{summary}{direct_thread_pairs}, 1,
    'direct/thread pair is recorded');
is($ledger->{direct_thread_pairs}[0]{direct},
    File::Spec->catfile($tests, 're', 'a.t'),
    'thread pair records its canonical direct test');
is($ledger->{direct_thread_pairs}[0]{thread},
    File::Spec->catfile($tests, 're', 'a_thr.t'),
    'thread pair records its canonical thread test');
is($ledger->{summary}{unresolved_references}, 1,
    'unresolved documented gate is explicit');

my @runner = grep { length } split /\n/, read_file($list_path);
is(scalar @runner, 5, 'runner list contains each canonical path once');
is(scalar(grep { /plain\.t\z/ } @runner), 1,
    'documented non-regex auxiliary test is retained once');

done_testing;

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!\n";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!\n";
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!\n";
    return $contents;
}
