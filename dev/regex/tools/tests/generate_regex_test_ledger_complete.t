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
my $tool = File::Spec->catfile($root, 'dev', 'regex', 'tools',
    'generate_regex_test_ledger.pl');
my $temporary = tempdir(CLEANUP => 1);
my $tests = File::Spec->catdir($temporary, 'perl5_t', 't');
my $units = File::Spec->catdir($temporary, 'unit');
make_path(map { File::Spec->catdir($tests, $_) } qw(re op uni japh lib));
make_path($units);

write_file(File::Spec->catfile($tests, 're', 'regex.t'), "qr/a/;\n");
write_file(File::Spec->catfile($tests, 're', 'paired.t'), "qr/a/;\n");
write_file(File::Spec->catfile($tests, 're', 'paired_thr.t'), "qr/a/;\n");
write_file(File::Spec->catfile($tests, 're', 'standalone_thr.t'), "qr/a/;\n");
write_file(File::Spec->catfile($tests, 'op', 'regex.t'), "'a' =~ /a/;\n");
write_file(File::Spec->catfile($tests, 'op', 'plain.t'), "print qq(ok 1\\n);\n");
write_file(File::Spec->catfile($tests, 'uni', 'property.t'), "qr/\\p{L}/;\n");
write_file(File::Spec->catfile($tests, 'japh', 'poem.t'), "print qq(ok 1\\n);\n");
write_file(File::Spec->catfile($tests, 'lib', 'README'), "not a test\n");
my $reference = File::Spec->catfile($temporary, 'reference.md');
write_file($reference, "No documented test additions.\n");

my $regex = generate('regex');
is($regex->{scope}, 'regex', 'default-compatible regex scope is recorded');
is($regex->{summary}{runner_files}, 6,
    'regex scope selects re plus regex-bearing op and uni files');
is($regex->{summary}{direct_thread_pairs}, 1,
    'only an existing direct/thread carrier pair is counted as a pair');
is_deeply($regex->{direct_thread_pairs}, [{
        direct => File::Spec->catfile($tests, 're', 'paired.t'),
        thread => File::Spec->catfile($tests, 're', 'paired_thr.t'),
    }], 'direct/thread pair inventory contains both existing carriers');
is($regex->{summary}{thread_only_tests}, 1,
    'thread-only regex tests are counted separately');
is_deeply($regex->{thread_only_tests}, [
        File::Spec->catfile($tests, 're', 'standalone_thr.t'),
    ], 'thread-only inventory retains the standalone threaded test');

my $complete = generate('complete');
is($complete->{scope}, 'complete', 'complete scope is recorded');
is($complete->{summary}{runner_files}, 8,
    'complete scope discovers every imported test recursively');
is_deeply($complete->{runner_files}, [sort map {
    File::Spec->catfile($tests, @$_)
} ([re => 'regex.t'], [re => 'paired.t'], [re => 'paired_thr.t'],
   [re => 'standalone_thr.t'], [op => 'plain.t'], [op => 'regex.t'],
   [uni => 'property.t'], [japh => 'poem.t'])],
    'complete runner list is canonical and excludes non-test files');

my $first = JSON::PP->new->canonical->encode($complete);
is(JSON::PP->new->canonical->encode(generate('complete')), $first,
    'complete discovery is byte-stable across runs');

done_testing;

sub generate {
    my ($scope) = @_;
    my $output = File::Spec->catfile($temporary, "$scope.json");
    system $^X, $tool,
        '--tests-root', $tests,
        '--unit-root', $units,
        '--reference', $reference,
        '--scope', $scope,
        '--output', $output;
    is($? >> 8, 0, "$scope ledger generation succeeds");
    return JSON::PP->new->decode(read_file($output));
}

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
