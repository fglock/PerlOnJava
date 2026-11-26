use strict;
use warnings;

use Archive::Tar;
use Config ();
use Digest::SHA ();
use File::Basename qw(dirname);
use File::Find qw(find);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use IPC::Open3 qw(open3);
use JSON::PP qw(encode_json decode_json);
use Symbol qw(gensym);
use Test::More;

my $script = File::Spec->rel2abs('dev/tools/cpan_import_sync.pl');
my $temporary = tempdir(CLEANUP => 1);
my $root = File::Spec->catdir($temporary, 'repo');
my $fixture = File::Spec->catdir($temporary, 'fixtures');
my $stage_root = File::Spec->catdir($temporary, 'stages');
my $registry_path = File::Spec->catfile($fixture, 'registry.json');
my $provider_path = File::Spec->catfile(
    $root, 'src', 'main', 'perl', 'lib', 'Object', 'Pad.pm');
my $provider_rel = 'src/main/perl/lib/Object/Pad.pm';
my $zlib_provider_path = File::Spec->catfile(
    $root, 'src', 'main', 'perl', 'lib', 'Compress', 'Raw', 'Zlib.pm');
my $zlib_provider_rel = 'src/main/perl/lib/Compress/Raw/Zlib.pm';
my $bzip_perl_path = File::Spec->catfile(
    $root, 'src', 'main', 'perl', 'lib', 'Compress', 'Raw', 'Bzip2.pm');
my $bzip_java_path = File::Spec->catfile($root, 'src', 'main', 'java', 'CompressRawBzip2.java');
my $catalog_path = File::Spec->catfile($root, 'gradle', 'libs.versions.toml');
make_path(dirname($provider_path), File::Spec->catdir($fixture, 'release'),
    File::Spec->catdir($fixture, 'archive'), $stage_root);
write_file($provider_path, "package Object::Pad; our \$VERSION = '0.66'; 1;\n");
write_file($zlib_provider_path,
    "package Compress::Raw::Zlib; our \$VERSION = '2.224'; 1;\n");
write_file($bzip_perl_path,
    "package Compress::Raw::Bzip2; our \$VERSION = '2.224'; 1;\n");
write_file($bzip_java_path, 'static final String XS_VERSION = "2.224";' . "\n");
write_file($catalog_path, 'commons-compress = "1.28.0"' . "\n");

my $consumer = {
    distribution => 'MooseX-LocalAttribute',
    source => 'https://metacpan.org/dist/MooseX-LocalAttribute',
    port_mode => 'full-import',
    upstream_distribution_version => '0.9',
    providers => [{
        kind => 'perl_version', module => 'MooseX::LocalAttribute',
        path => 'src/main/perl/lib/MooseX/LocalAttribute.pm', version => '0.8',
        same_version_as_upstream => JSON::PP::true,
        contract => 'full-import module version',
    }],
    files => [
        { source => 'lib/MooseX/LocalAttribute.pm', target => 'src/main/perl/lib/MooseX/LocalAttribute.pm' },
        { source => 't', target => 'src/test/resources/module/MooseX-LocalAttribute/t' },
    ],
    update_policy => 'stage-and-manual-port',
    tests => [],
};
my $object_pad = {
    distribution => 'Object-Pad',
    source => 'https://metacpan.org/dist/Object-Pad',
    port_mode => 'rewritten',
    upstream_distribution_version => '0.66',
    providers => [{
        kind => 'perl_version', module => 'Object::Pad', path => $provider_rel,
        version => '0.66', contract => 'compatibility fixture',
    }],
    update_policy => 'stage-and-manual-port',
    tests => [],
};
my $zlib = {
    distribution => 'Compress-Raw-Zlib',
    source => 'https://metacpan.org/dist/Compress-Raw-Zlib',
    port_mode => 'rewritten',
    upstream_distribution_version => '2.224',
    providers => [{
        kind => 'perl_version', module => 'Compress::Raw::Zlib', path => $zlib_provider_rel,
        version => '2.224', contract => 'compatibility fixture',
    }],
    update_policy => 'stage-and-manual-port',
    tests => [],
};
my $bzip = {
    distribution => 'Compress-Raw-Bzip2',
    source => 'https://metacpan.org/dist/Compress-Raw-Bzip2',
    port_mode => 'rewritten',
    upstream_distribution_version => '2.224',
    providers => [
        {
            kind => 'perl_version', module => 'Compress::Raw::Bzip2',
            path => 'src/main/perl/lib/Compress/Raw/Bzip2.pm', version => '2.224',
            same_version_as_upstream => JSON::PP::true, contract => 'upstream package API',
        },
        {
            kind => 'java_static_string', provider => 'CompressRawBzip2',
            path => 'src/main/java/CompressRawBzip2.java', symbol => 'XS_VERSION',
            version => '2.224', same_version_as_upstream => JSON::PP::true,
            contract => 'CPAN XS API compatibility',
        },
        {
            kind => 'gradle_catalog', provider => 'commons-compress',
            path => 'gradle/libs.versions.toml', symbol => 'commons-compress',
            version => '1.28.0', contract => 'independent backing library version',
        },
    ],
    update_policy => 'stage-and-manual-port',
    tests => [],
};
write_json($registry_path,
    { schema_version => 1, modules => [$object_pad, $zlib, $bzip, $consumer] });

my ($status, $output) = run_sync('--apply', '--update');
isnt($status, 0, 'conflicting update modes are rejected');
like($output, qr/Usage: cpan_import_sync\.pl/, 'mode conflict prints command usage');

($status, $output) = run_sync('--check', '--module', 'Object-Pad');
is($status, 0, 'offline check accepts a consistent provider');
like($output, qr/contracts are consistent \(1 distributions\)/,
    'module selection limits the offline report');

write_file($bzip_java_path, 'static final String XS_VERSION = "2.225";' . "\n");
($status, $output) = run_sync('--check', '--module', 'Compress-Raw-Bzip2');
isnt($status, 0, 'offline check detects Java XS compatibility drift');
like($output, qr/declares 2\.225; registry pins 2\.224/,
    'Java provider mismatch reports actual and pinned versions');
write_file($bzip_java_path, 'static final String XS_VERSION = "2.224";' . "\n");

write_file($catalog_path, 'commons-compress = "1.28.1"' . "\n");
($status, $output) = run_sync('--check', '--module', 'Compress-Raw-Bzip2');
isnt($status, 0, 'offline check detects independent backing-library drift');
like($output, qr/declares 1\.28\.1; registry pins 1\.28\.0/,
    'backing-library mismatch reports actual and pinned versions');
write_file($catalog_path, 'commons-compress = "1.28.0"' . "\n");

write_file($provider_path, "package Object::Pad; our \$VERSION = '0.67'; 1;\n");
($status, $output) = run_sync('--check', '--module', 'Object-Pad');
isnt($status, 0, 'offline check rejects provider drift');
like($output, qr/declares 0\.67; registry pins 0\.66/,
    'drift output identifies actual and pinned versions');
write_file($provider_path, "package Object::Pad; our \$VERSION = '0.66'; 1;\n");

write_candidate('0.800', 0);
my $stage_800 = File::Spec->catdir($stage_root, 'below-0800');
($status, $output) = run_sync('--update', '--module', 'MooseX-LocalAttribute',
    '--fixture-dir', $fixture, '--stage-dir', $stage_800);
isnt($status, 0, 'candidate is rejected for requiring Object::Pad 0.800');
like($output, qr/Object::Pad >= 0\.800.*provider.*0\.66/s,
    '0.800 diagnostic reports the effective bundled version');
ok(!-e File::Spec->catdir($stage_800, 'MooseX-LocalAttribute-1.0'),
    'unsatisfied prerequisite leaves no staged candidate');

write_candidate('0.805', 0);
my $stage_805 = File::Spec->catdir($stage_root, 'below-0805');
($status, $output) = run_sync('--update', '--module', 'MooseX-LocalAttribute',
    '--fixture-dir', $fixture, '--stage-dir', $stage_805);
isnt($status, 0, 'candidate is rejected for requiring Object::Pad 0.805');
like($output, qr/Object::Pad >= 0\.805.*0\.66/s,
    '0.805 diagnostic reports the effective bundled version');

write_candidate('2.225', 0, 'Compress::Raw::Zlib');
my $stage_old_zlib = File::Spec->catdir($stage_root, 'old-zlib-provider');
($status, $output) = run_sync('--update', '--module', 'MooseX-LocalAttribute',
    '--fixture-dir', $fixture, '--stage-dir', $stage_old_zlib);
isnt($status, 0, 'consumer is rejected when it needs newer Compress::Raw::Zlib');
like($output, qr/Compress::Raw::Zlib >= 2\.225.*2\.224/s,
    'compression-provider diagnostic uses the effective bundled Perl version');

my $overlay = File::Spec->catdir($temporary, 'dependency-blib', 'lib');
make_path(File::Spec->catdir($overlay, 'Object'));
write_file(File::Spec->catfile($overlay, 'Object', 'Pad.pm'),
    "package Object::Pad; our \$VERSION = '0.805'; 1;\n");
write_candidate('0.800', 0);
my $stage_overlay = File::Spec->catdir($stage_root, 'overlay-ok');
($status, $output) = run_sync('--update', '--module', 'MooseX-LocalAttribute',
    '--overlay-dir', $overlay, '--fixture-dir', $fixture,
    '--stage-dir', $stage_overlay);
is($status, 0, 'effective dependency blib overlay satisfies the minimum');
my $candidate = File::Spec->catdir($stage_overlay, 'MooseX-LocalAttribute-1.0');
ok(-f File::Spec->catfile($candidate, 'PERLONJAVA-PORTING.txt'),
    'successful update stages a provenance and manual-port report');
my @staged_sources;
find({
    wanted => sub {
        push @staged_sources, $File::Find::name
            if -f $File::Find::name && $File::Find::name =~ m{/MooseX/LocalAttribute\.pm\z};
    },
    no_chdir => 1,
}, $candidate);
ok(@staged_sources, 'verified release source is staged');
ok(-f File::Spec->catfile($candidate, 'candidate', 'src', 'main', 'perl', 'lib',
        'MooseX', 'LocalAttribute.pm'),
    'full source mappings are prepared beneath the staged candidate');
ok(-f File::Spec->catfile($candidate, 'candidate', 'src', 'test', 'resources', 'module',
        'MooseX-LocalAttribute', 't', 'upstream.t'),
    'upstream test mappings are prepared beneath the staged candidate');
ok(-f $provider_path, 'staging does not overwrite a bundled provider');

write_candidate('0.800', 0);
my $release_path = File::Spec->catfile($fixture, 'release', 'MooseX-LocalAttribute.json');
my $release = read_json($release_path);
$release->{checksum_sha256} = '0' x 64;
write_json($release_path, $release);
my $bad_checksum_stage = File::Spec->catdir($stage_root, 'bad-checksum');
($status, $output) = run_sync('--update', '--module', 'MooseX-LocalAttribute',
    '--overlay-dir', $overlay, '--fixture-dir', $fixture,
    '--stage-dir', $bad_checksum_stage);
isnt($status, 0, 'archive with a mismatched checksum is rejected');
like($output, qr/SHA-256 mismatch/, 'checksum failure is explicit');
ok(!-e File::Spec->catdir($bad_checksum_stage, 'MooseX-LocalAttribute-1.0'),
    'checksum failure creates no stage');

write_candidate('0.800', 1);
my $release2 = read_json($release_path);
my $archive2 = File::Spec->catfile($fixture, 'archive',
    'MooseX-LocalAttribute-1.0.tar.gz');
$release2->{checksum_sha256} = sha256_file($archive2);
write_json($release_path, $release2);
my $unsafe_stage = File::Spec->catdir($stage_root, 'unsafe-archive');
($status, $output) = run_sync('--update', '--module', 'MooseX-LocalAttribute',
    '--overlay-dir', $overlay, '--fixture-dir', $fixture,
    '--stage-dir', $unsafe_stage);
isnt($status, 0, 'archive with a traversal path is rejected');
like($output, qr/unsafe archive path/, 'unsafe path is reported');
ok(!-e File::Spec->catfile($temporary, 'escape.txt'),
    'archive path validation prevents traversal outside staging');

write_candidate('0.800', 0);
my $stable_release_path = File::Spec->catfile($fixture, 'release', 'MooseX-LocalAttribute.json');
my $stable_release = read_json($stable_release_path);
write_json($stable_release_path, {
    releases => [
        { %$stable_release, version => '9.9', maturity => 'developer', date => '2026-12-01' },
        { %$stable_release, version => '1.0', maturity => 'released', date => '2026-01-01' },
    ],
});
my $stable_stage = File::Spec->catdir($stage_root, 'stable-selection');
($status, $output) = run_sync('--update', '--module', 'MooseX-LocalAttribute',
    '--overlay-dir', $overlay, '--fixture-dir', $fixture, '--stage-dir', $stable_stage);
diag($output) if $status;
is($status, 0, 'release lookup selects a stable release when a newer trial exists');
ok(-d File::Spec->catdir($stable_stage, 'MooseX-LocalAttribute-1.0'),
    'stable selection stages the released version');

write_candidate('0.800', 0, undef, 'Wrong-Distribution');
my $mismatch_stage = File::Spec->catdir($stage_root, 'distribution-mismatch');
($status, $output) = run_sync('--update', '--module', 'MooseX-LocalAttribute',
    '--overlay-dir', $overlay, '--fixture-dir', $fixture, '--stage-dir', $mismatch_stage);
isnt($status, 0, 'candidate metadata with a different distribution is rejected');
like($output, qr/META\.json distribution name does not match/,
    'distribution mismatch explains the candidate metadata error');

write_json($stable_release_path, { releases => [
    { %$stable_release, version => '9.9', maturity => 'developer' },
] });
($status, $output) = run_sync('--update', '--module', 'MooseX-LocalAttribute',
    '--fixture-dir', $fixture, '--stage-dir', File::Spec->catdir($stage_root, 'no-stable'));
isnt($status, 0, 'release lookup rejects a distribution with no stable release');
like($output, qr/no stable release/, 'absence of a stable release is explicit');

my $apply_root = File::Spec->catdir($temporary, 'apply-repo');
my $apply_registry = File::Spec->catfile($apply_root, 'dev', 'tools', 'cpan-import-registry.json');
my $patch_rel = 'dev/tools/cpan-import-patches/MooseX-LocalAttribute/fix.patch';
my $apply_registry_entry = {
    schema_version => 1,
    modules => [{ %$consumer, tests => [], patches => [{ path => $patch_rel, strip => 1 }] }],
};
write_json($apply_registry, $apply_registry_entry);
write_file(File::Spec->catfile($apply_root, split m{/}, $patch_rel), <<'PATCH');
--- a/lib/MooseX/LocalAttribute.pm
+++ b/lib/MooseX/LocalAttribute.pm
@@ -1 +1,2 @@
-package MooseX::LocalAttribute; our $VERSION = '0.8'; 1;
+package MooseX::LocalAttribute; our $VERSION = '0.8';
+our $PERLONJAVA_PATCHED = 1; 1;
PATCH
write_file(File::Spec->catfile($apply_root, 'src', 'test', 'java', 'org', 'perlonjava',
    'ModuleTestExecutionTest.java'), 'class ModuleTestExecutionTest {}' . "\n");
write_file(File::Spec->catfile($apply_root, 'src', 'main', 'perl', 'lib', 'MooseX',
    'LocalAttribute.pm'), "package MooseX::LocalAttribute; our \$VERSION = '0.8'; 1;\n");
write_file(File::Spec->catfile($apply_root, 'src', 'test', 'resources', 'module',
    'MooseX-LocalAttribute', 't', 'obsolete.t'), "die 'stale fixture should be removed';\n");
write_file(File::Spec->catfile($apply_root, 'Makefile'), 'all:' . "\n");
system('git', '-C', $apply_root, 'init', '--quiet') == 0 or die "cannot initialize apply fixture repository\n";
system('git', '-C', $apply_root, 'config', 'user.name', 'CPAN updater test') == 0
    or die "cannot configure fixture repository identity\n";
system('git', '-C', $apply_root, 'config', 'user.email', 'cpan-updater-test@example.invalid') == 0
    or die "cannot configure fixture repository identity\n";
system('git', '-C', $apply_root, 'add', '-A') == 0 or die "cannot stage fixture repository\n";
system('git', '-C', $apply_root, 'commit', '--quiet', '-m', 'fixture') == 0
    or die "cannot commit fixture repository\n";

my $fake_bin = File::Spec->catdir($temporary, 'fake-bin');
make_path($fake_bin);
write_file(File::Spec->catfile($fake_bin, 'nice'), "#!/bin/sh\nshift 2\nexec \"\$@\"\n");
write_file(File::Spec->catfile($fake_bin, 'make'), <<'MAKE');
#!/bin/sh
if [ "$1" = "-C" ]; then cd "$2" || exit 1; shift 2; fi
if [ "${FAKE_MAKE_FAIL:-}" = "1" ]; then exit 9; fi
if [ "$1" = "test-bundled-modules" ]; then
  mkdir -p build/test-results/testModule
  if [ "${FAKE_TEST_UNDISCOVERED:-}" = "1" ]; then
    printf '%s\n' '<testsuite/>' \
      > build/test-results/testModule/TEST-org.perlonjava.ModuleTestExecutionTest.xml
  else
    printf '%s\n' '<testsuite><testcase name="module/MooseX-LocalAttribute/t/upstream.t"/></testsuite>' \
    > build/test-results/testModule/TEST-org.perlonjava.ModuleTestExecutionTest.xml
  fi
fi
exit 0
MAKE
chmod 0755, File::Spec->catfile($fake_bin, 'nice'), File::Spec->catfile($fake_bin, 'make');

my $original_root = $root;
my $original_registry = $registry_path;
$root = $apply_root;
$registry_path = $apply_registry;
write_candidate('0.800', 0);
my $apply_stage = File::Spec->catdir($stage_root, 'apply-success');
local $ENV{PATH} = $fake_bin . $Config::Config{path_sep} . $ENV{PATH};
($status, $output) = run_sync('--apply', '--module', 'MooseX-LocalAttribute',
    '--fixture-dir', $fixture, '--stage-dir', $apply_stage);
diag($output) if $status;
is($status, 0, 'apply validates the staged update in a temporary worktree');
ok(-f File::Spec->catfile($apply_root, 'src', 'main', 'perl', 'lib', 'MooseX',
        'LocalAttribute.pm'),
    'validated full-import source is applied to the clean checkout');
ok(-f File::Spec->catfile($apply_root, 'src', 'test', 'resources', 'module',
        'MooseX-LocalAttribute', 't', 'upstream.t'),
    'validated upstream tests are applied with their source');
like(read_file(File::Spec->catfile($apply_root, 'src', 'main', 'perl', 'lib', 'MooseX',
        'LocalAttribute.pm')), qr/\$VERSION\s*=\s*'1\.0'/,
    'full-import provider version target follows the selected release');
like(read_file(File::Spec->catfile($apply_root, 'src', 'main', 'perl', 'lib', 'MooseX',
        'LocalAttribute.pm')), qr/\$PERLONJAVA_PATCHED\s*=\s*1/,
    'configured PerlOnJava patch is applied before validation');
ok(!-e File::Spec->catfile($apply_root, 'src', 'test', 'resources', 'module',
        'MooseX-LocalAttribute', 't', 'obsolete.t'),
    'removed upstream tests are deleted from the refreshed fixture');
my $applied_registry = read_json($apply_registry);
is($applied_registry->{modules}[0]{upstream_distribution_version}, '1.0',
    'apply updates the registry only after validation succeeds');

my $failure_root = File::Spec->catdir($temporary, 'apply-failure-repo');
system('git', 'clone', '--quiet', $apply_root, $failure_root) == 0
    or die "cannot clone failure fixture repository\n";
$root = $failure_root;
$registry_path = File::Spec->catfile($failure_root, 'dev', 'tools', 'cpan-import-registry.json');
local $ENV{FAKE_MAKE_FAIL} = 1;
my $failed_apply_stage = File::Spec->catdir($stage_root, 'apply-failure');
($status, $output) = run_sync('--apply', '--module', 'MooseX-LocalAttribute',
    '--fixture-dir', $fixture, '--stage-dir', $failed_apply_stage);
diag($output) if $status;
isnt($status, 0, 'failed worktree validation rejects apply');
like(read_file(File::Spec->catfile($failure_root, 'src', 'main', 'perl', 'lib', 'MooseX',
        'LocalAttribute.pm')), qr/\$VERSION\s*=\s*'0\.8'/,
    'failed validation leaves the checkout source unchanged');
ok(-f File::Spec->catfile($failure_root, 'src', 'test', 'resources', 'module',
        'MooseX-LocalAttribute', 't', 'obsolete.t'),
    'failed validation retains the prior fixture files');
my $failure_status = `git -C "$failure_root" status --porcelain`;
diag(`git -C "$failure_root" diff -- dev/tools/cpan-import-registry.json`) if length $failure_status;
is($failure_status, '', 'failed validation leaves no tracked checkout changes');

{
    local $ENV{FAKE_MAKE_FAIL} = 0;
    local $ENV{FAKE_TEST_UNDISCOVERED} = 1;
    ($status, $output) = run_sync('--apply', '--module', 'MooseX-LocalAttribute',
        '--fixture-dir', $fixture, '--stage-dir', File::Spec->catdir($stage_root, 'audit-failure'));
}
isnt($status, 0, 'apply rejects refreshed tests missing from the discovery report');
like($output, qr/not discovered by ModuleTestExecutionTest/,
    'test discovery audit identifies the omitted upstream test');
is(`git -C "$failure_root" status --porcelain`, '',
    'test discovery failure leaves the checkout unchanged');

$root = $original_root;
$registry_path = $original_registry;

done_testing();

sub write_candidate {
    my ($minimum, $unsafe, $requirement_package, $meta_name) = @_;
    $requirement_package //= 'Object::Pad';
    my $dist = 'MooseX-LocalAttribute';
    my $version = '1.0';
    my $archive = Archive::Tar->new;
    my $meta = {
        name => $meta_name || $dist,
        version => $version,
        prereqs => { runtime => { requires => { $requirement_package => $minimum } } },
    };
    $archive->add_data("$dist-$version/META.json", encode_json($meta),
        { mode => 0644 });
    $archive->add_data("$dist-$version/lib/MooseX/LocalAttribute.pm",
        "package MooseX::LocalAttribute; our \$VERSION = '0.8'; 1;\n", { mode => 0644 });
    $archive->add_data("$dist-$version/t/upstream.t",
        "print \"1..1\\nok 1 - fixture upstream test\\n\";\n", { mode => 0644 });
    $archive->add_data('../../escape.txt', 'bad', { mode => 0644 }) if $unsafe;
    my $archive_path = File::Spec->catfile($fixture, 'archive', "$dist-$version.tar.gz");
    $archive->write($archive_path, Archive::Tar::COMPRESS_GZIP())
        or die "cannot write fixture archive\n";
    my $release = {
        name => $dist,
        version => $version,
        maturity => 'released',
        authorized => JSON::PP::true,
        download_url => "https://cpan.metacpan.org/authors/id/M/MO/MOUSER/$dist-$version.tar.gz",
        checksum_sha256 => sha256_file($archive_path),
    };
    write_json(File::Spec->catfile($fixture, 'release', "$dist.json"), $release);
}

sub run_sync {
    my (@args) = @_;
    my $error = gensym;
    my $pid = open3(undef, my $stdout_fh, $error, $^X, $script,
        '--root', $root, '--registry', $registry_path, @args);
    my $stdout = do { local $/; <$stdout_fh> // '' };
    my $stderr = do { local $/; <$error> // '' };
    waitpid($pid, 0);
    my $exit = $? == -1 ? 255 : ($? >> 8);
    return ($exit, $stdout . $stderr);
}

sub write_json {
    my ($path, $value) = @_;
    make_path(dirname($path));
    write_file($path, encode_json($value) . "\n");
}

sub read_json {
    my ($path) = @_;
    return decode_json(read_file($path));
}

sub write_file {
    my ($path, $contents) = @_;
    make_path(dirname($path));
    open my $fh, '>:raw', $path or die "cannot write $path: $!";
    print {$fh} $contents or die "cannot write $path: $!";
    close $fh or die "cannot close $path: $!";
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "cannot read $path: $!";
    local $/;
    return <$fh>;
}

sub sha256_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "cannot read $path: $!";
    my $sha = Digest::SHA->new(256);
    $sha->addfile($fh);
    return $sha->hexdigest;
}
