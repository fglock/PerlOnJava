use strict;
use warnings;
use Test::More;
use Digest::SHA qw(sha256_hex);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IPC::Open3;
use JSON::PP;
use Symbol qw(gensym);

my $repo = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $pipeline = File::Spec->catfile($repo, 'dev', 'regex', 'tools', 'generate_perl_unicode_data.pl');
my $temporary = tempdir(CLEANUP => 1);
my $unicode = File::Spec->catdir($temporary, 'unicore');
my $perl_root = File::Spec->catdir($temporary, 'perl5');
my $tools = File::Spec->catdir($temporary, 'tools');
my $output_dir = File::Spec->catdir($temporary, 'generated');
make_path($unicode, $perl_root, $tools, $output_dir);

my $version = "17.0.0\n";
my $notice = "# © 2025 Unicode®, Inc.\n"
    . "# Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the U.S. and other countries.\n"
    . "# For terms of use and license, see https://www.unicode.org/terms_of_use.html\n";
my $source = "# test Unicode table\n" . $notice . "0041 ; Example\n";
my $generated = $source . "# pinned Perl generator source\n";
write_file(File::Spec->catfile($unicode, 'version'), $version);
write_file(File::Spec->catfile($unicode, 'table.txt'), $source);
my $perl_source = "# pinned Perl generator source\n";
write_file(File::Spec->catfile($perl_root, 'source.pl'), $perl_source);
write_file(File::Spec->catfile($perl_root, 'patchlevel.h'), "#define PERL_REVISION 5\n#define PERL_VERSION 45\n#define PERL_SUBVERSION 2\n");
system('git', '-C', $perl_root, 'init', '-q') == 0 or die 'Cannot initialize fixture checkout';
system('git', '-C', $perl_root, 'config', 'user.email', 'test@example.invalid') == 0 or die 'Cannot configure fixture checkout';
system('git', '-C', $perl_root, 'config', 'user.name', 'Fixture') == 0 or die 'Cannot configure fixture checkout';
system('git', '-C', $perl_root, 'add', '.') == 0 or die 'Cannot stage fixture checkout';
system('git', '-C', $perl_root, 'commit', '-qm', 'fixture') == 0 or die 'Cannot commit fixture checkout';

write_file(File::Spec->catfile($tools, 'generate.pl'), <<'GENERATOR');
#!/usr/bin/env perl
use strict;
use warnings;
binmode STDOUT, ':raw';
for my $path ("$ENV{PERLONJAVA_UNICODE_ROOT}/table.txt", "$ENV{PERLONJAVA_PERL_ROOT}/source.pl") {
    open my $input, '<:raw', $path or die $!;
    print while <$input>;
    close $input;
}
GENERATOR
my $output = File::Spec->catfile($output_dir, 'Data.java');
write_file($output, "stale\n");

my $manifest = File::Spec->catfile($temporary, 'manifest.json');
write_manifest($manifest, {
    schema_version => 1,
    unicode_version => '17.0.0',
    perl_version => '5.44.0',
    perl_commit => 'test-perl-commit',
    version_sha256 => sha256_hex($version),
    shared_sources => {'table.txt' => sha256_hex($source)},
    generators => [{
        name => 'example',
        generator => 'tools/generate.pl',
        output => 'generated/Data.java',
        output_sha256 => sha256_hex($generated),
        sources => {},
        perl_sources => {'source.pl' => sha256_hex($perl_source)},
    }],
});

my ($status, $log) = run_pipeline('--list');
is($status, 0, '--list succeeds without reading source data');
like($log, qr/^example\s+tools\/generate\.pl\s+generated\/Data\.java\s+/m,
    '--list exposes manifest provenance');

# Schema 2 deliberately follows an explicitly supplied current checkout.  The
# old hashes remain provenance for a later regeneration, but do not freeze the
# checkout to a historical Perl commit.
my $current_manifest = File::Spec->catfile($temporary, 'current-manifest.json');
my $current_value = decode_json(read_file($manifest));
$current_value->{schema_version} = 2;
$current_value->{perl_source_policy} = 'current-checkout';
$current_value->{perl_commit} = 'stale-current-provenance';
write_manifest($current_manifest, $current_value);
my $moving_source = "# moving current source\n" . $notice;
write_file(File::Spec->catfile($unicode, 'table.txt'), $moving_source);
write_file(File::Spec->catfile($unicode, 'version'), "18.0.0\n");
($status, $log) = run_pipeline_with_manifest($current_manifest, '--check');
is($status, 255, 'current-checkout check reaches generated-output comparison');
like($log, qr/generated SHA-256 mismatch/,
    'stale provenance does not mask the reproducible output mismatch');
write_file(File::Spec->catfile($unicode, 'table.txt'), "missing notice\n");
($status, $log) = run_pipeline_with_manifest($current_manifest, '--check');
is($status, 255, 'current-checkout check rejects a missing Unicode notice');
like($log, qr/does not preserve the Unicode copyright notice/,
    'missing current-source notice is diagnosed');
write_file(File::Spec->catfile($unicode, 'table.txt'), $moving_source);
($status, $log) = run_pipeline_with_manifest($current_manifest, '--refresh');
is($status, 0, 'refresh accepts a valid newer current Unicode version');
is(decode_json(read_file($current_manifest))->{unicode_version}, '18.0.0',
    'refresh records the newer Unicode version as provenance');
($status, $log) = run_pipeline_with_manifest($current_manifest, '--check');
is($status, 0, 'newer refreshed current checkout is deterministic and checkable');
write_file(File::Spec->catfile($unicode, 'version'), "not-a-version\n");
($status, $log) = run_pipeline_with_manifest($current_manifest, '--refresh');
is($status, 255, 'refresh rejects a malformed current Unicode version');
like($log, qr/Malformed current Unicode version/, 'malformed version is diagnosed');
write_file(File::Spec->catfile($unicode, 'version'), $version);
write_file(File::Spec->catfile($unicode, 'table.txt'), $source);
write_file($output, "stale\n");

($status, $log) = run_pipeline('--check');
is($status, 1, '--check rejects stale output');
like($log, qr/example: stale generated output/, 'stale output is identified');
is(read_file($output), "stale\n", '--check never changes stale output');

($status, $log) = run_pipeline();
is($status, 0, 'update publishes deterministic output');
is(read_file($output), $generated, 'update publishes exact generated bytes');

($status, $log) = run_pipeline('--check');
is($status, 0, 'first current-output check succeeds');
like($log, qr/example: current \([0-9a-f]{64}\)/, 'check reports the generated checksum');
my $after_first_check = read_file($output);
($status, $log) = run_pipeline('--check');
is($status, 0, 'second current-output check succeeds');
is(read_file($output), $after_first_check, 'consecutive checks are byte-idempotent');

write_file(File::Spec->catfile($unicode, 'table.txt'), "corrupt\n");
($status, $log) = run_pipeline('--check');
is($status, 255, 'source checksum mismatch fails before generation');
like($log, qr/table\.txt SHA-256 mismatch/, 'source checksum diagnostic names the input');
is(read_file($output), $generated, 'source failure leaves published output untouched');
write_file(File::Spec->catfile($unicode, 'table.txt'), $source);

write_file(File::Spec->catfile($perl_root, 'source.pl'), "corrupt\n");
($status, $log) = run_pipeline('--check');
is($status, 255, 'Perl source checksum mismatch fails before generation');
like($log, qr/source\.pl SHA-256 mismatch/, 'Perl source checksum diagnostic names the input');
is(read_file($output), $generated, 'Perl source failure leaves published output untouched');
write_file(File::Spec->catfile($perl_root, 'source.pl'), $perl_source);

write_file(File::Spec->catfile($tools, 'generate.pl'), <<'NONDETERMINISTIC');
#!/usr/bin/env perl
use strict;
use warnings;
my $counter = "$ENV{PERLONJAVA_UNICODE_ROOT}/counter";
my $value = 0;
if (open my $input, '<', $counter) {
    chomp($value = <$input>);
    close $input;
}
open my $output, '>', $counter or die $!;
print {$output} $value + 1, "\n";
close $output;
print $value + 1, "\n";
NONDETERMINISTIC
($status, $log) = run_pipeline('--check');
is($status, 255, 'consecutive-output mismatch is fatal');
like($log, qr/generator is nondeterministic across consecutive runs/,
    'nondeterminism has a focused diagnostic');
is(read_file($output), $generated, 'nondeterminism leaves published output untouched');

done_testing;

sub run_pipeline {
    return run_pipeline_with_manifest($manifest, @_);
}

sub run_pipeline_with_manifest {
    my ($selected_manifest, @arguments) = @_;
    my $error = gensym;
    my $pid = open3(undef, my $stdout, $error,
        $^X, $pipeline,
        '--root', $temporary,
        '--manifest', $selected_manifest,
        '--unicode-root', $unicode,
        '--perl-root', $perl_root,
        @arguments);
    local $/;
    my $log = (<$stdout> // '') . (<$error> // '');
    waitpid($pid, 0);
    return ($? >> 8, $log);
}

sub write_manifest {
    my ($path, $value) = @_;
    write_file($path, JSON::PP->new->canonical->pretty->encode($value));
}

sub write_file {
    my ($path, $bytes) = @_;
    open my $output, '>:raw', $path or die "Cannot write $path: $!";
    print {$output} $bytes or die "Cannot write $path: $!";
    close $output or die "Cannot close $path: $!";
}

sub read_file {
    my ($path) = @_;
    open my $input, '<:raw', $path or die "Cannot read $path: $!";
    local $/;
    my $bytes = <$input>;
    close $input or die "Cannot close $path: $!";
    return $bytes;
}
