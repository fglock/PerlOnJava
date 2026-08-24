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
my $tool = File::Spec->catfile(
    $root, 'dev', 'tools', 'generate_regex_test_ledger.pl');
my $temporary = tempdir(CLEANUP => 1);
my $tests = File::Spec->catdir($temporary, 'perl5_t', 't');
my $units = File::Spec->catdir($temporary, 'unit');
my $japh = File::Spec->catfile($tests, 'japh', 'abigail.t');
my $nested = File::Spec->catfile(
    $tests, 'porting', 'nested-dir', 'dotted.name-test.t');
my $outside = File::Spec->catfile($temporary, 'outside.t');
make_path(File::Spec->catdir($tests, 're'));
make_path(File::Spec->catdir($tests, 'japh'));
make_path(File::Spec->catdir($tests, 'porting', 'nested-dir'));
make_path($units);
write_file(File::Spec->catfile($tests, 're', 'basic.t'), "qr/basic/;\n");
write_file($japh, "qr/abigail/;\n");
write_file($nested, "qr/nested/;\n");
write_file($outside, "qr/outside/;\n");
my $escape = File::Spec->catfile($tests, 'porting', 'nested-dir', 'escape.t');
my $symlink_supported = symlink $outside, $escape;
my $reference = File::Spec->catfile($temporary, 'reference.md');
write_file($reference,
    "Imported gates: `japh/abigail.t`, "
    . "`porting/nested-dir/dotted.name-test.t`; invalid: "
    . "`../outside.t`, `japh/./abigail.t`, "
    . "`porting/nested-dir/escape.t`, `escape.t`.\n");
my $ledger = File::Spec->catfile($temporary, 'ledger.json');
my $runner = File::Spec->catfile($temporary, 'runner.list');

system $^X, $tool,
    '--tests-root', $tests,
    '--unit-root', $units,
    '--reference', $reference,
    '--runner-list', $runner,
    '--output', $ledger;
is($? >> 8, 0, 'nested-reference ledger generation succeeds');

my $document = JSON::PP->new->decode(read_file($ledger));
my %unresolved = map { $_->{spelling} => 1 }
    @{$document->{unresolved_references}};
ok($unresolved{'../outside.t'},
    'dot-dot path to an existing outside file remains unresolved');
ok($unresolved{'japh/./abigail.t'},
    'dot path beneath the tests root remains unresolved');
SKIP: {
    skip 'symlink creation is unavailable on this platform', 2
        unless $symlink_supported;
    ok($unresolved{'porting/nested-dir/escape.t'},
        'nested symlink escape remains unresolved');
    ok($unresolved{'escape.t'},
        'bare-name scan rejects a symlink escape');
}
my @files = grep { length } split /\n/, read_file($runner);
ok(grep($_ eq $japh, @files),
    'documented nested imported gate joins the runner list');
ok(grep($_ eq $nested, @files),
    'multi-level dotted and hyphenated imported gate joins the runner list');

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
