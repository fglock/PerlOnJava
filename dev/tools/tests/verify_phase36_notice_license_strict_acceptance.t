use strict;
use warnings;

use Digest::SHA qw(sha256_hex);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $repository = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $temporary = tempdir(CLEANUP => 1);
my $source = File::Spec->catdir($temporary, 'source');
my $jar_tree = File::Spec->catdir($temporary, 'jar-tree');
my $jar = File::Spec->catfile($temporary, 'standalone.jar');
my $sbom = File::Spec->catfile($temporary, 'sbom.json');
my $notice_output = File::Spec->catfile($temporary, 'notice-license.json');
my $joni_ref = 'pkg:maven/org.jruby.joni/joni@2.2.7?type=jar';
my $jcodings_ref = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';

make_path(File::Spec->catdir($source, 'third_party', 'joni'));
make_path(File::Spec->catdir($source, 'third_party', 'licenses'));
make_path(File::Spec->catdir($jar_tree, 'META-INF', 'licenses'));

my %contract = (
    'joni-license' => {
        source => File::Spec->catfile('third_party', 'joni', 'LICENSE'),
        entry => File::Spec->catfile('META-INF', 'licenses', 'joni-LICENSE.txt'),
    },
    'joni-notice' => {
        source => File::Spec->catfile(
            'third_party', 'joni', 'PERLONJAVA-NOTICE.md'),
        entry => File::Spec->catfile(
            'META-INF', 'licenses', 'joni-PERLONJAVA-NOTICE.md'),
    },
    'jcodings-license' => {
        source => File::Spec->catfile(
            'third_party', 'licenses', 'jcodings-LICENSE.txt'),
        entry => File::Spec->catfile(
            'META-INF', 'licenses', 'jcodings-LICENSE.txt'),
    },
);

for my $item (values %contract) {
    my $contents = read_file(File::Spec->catfile($repository, $item->{source}));
    write_file(File::Spec->catfile($source, $item->{source}), $contents);
    write_file(File::Spec->catfile($jar_tree, $item->{entry}), $contents);
}

is(system('jar', 'cf', $jar, '-C', $jar_tree, '.') >> 8, 0,
    'green fixture JAR is created');

write_file($sbom, JSON::PP->new->canonical->encode({
    bomFormat => 'CycloneDX',
    metadata => {
        component => { 'bom-ref' => 'perlonjava', name => 'perlonjava' },
    },
    components => [
        {
            type => 'library', group => 'org.jruby.joni', name => 'joni',
            version => '2.2.7', 'bom-ref' => $joni_ref, purl => $joni_ref,
            licenses => [{ license => { id => 'MIT' } }],
            properties => [{ name => 'perlonjava:vendored', value => 'true' }],
        },
        {
            type => 'library', group => 'org.jruby.jcodings', name => 'jcodings',
            version => '1.0.64', 'bom-ref' => $jcodings_ref, purl => $jcodings_ref,
            licenses => [{ license => { id => 'MIT' } }],
        },
    ],
    dependencies => [
        { ref => 'perlonjava', dependsOn => [$joni_ref, $jcodings_ref] },
        { ref => $joni_ref, dependsOn => [$jcodings_ref] },
    ],
}));

my $verifier = File::Spec->catfile(
    $repository, 'dev', 'tools', 'verify_phase36_notice_license.pl');
is(system($^X, $verifier,
        '--source-root', $source, '--jar', $jar,
        '--sbom', $sbom, '--output', $notice_output) >> 8,
    0, 'notice verifier emits a green record');
my $notice = load_json($notice_output);
ok($notice->{verified}, 'notice record is verified');

my $requirements_path = File::Spec->catfile(
    $repository, 'dev', 'tools', 'phase36_acceptance_requirements.json');
my $requirements = load_json($requirements_path);
my $baseline = $requirements->{baseline_sha256};
like($baseline, qr/\A[0-9a-f]{64}\z/,
    'authoritative requirements retain the baseline identity');
$requirements->{required_gates} = [
    grep { ($_->{id} // '') eq 'notice-license' }
        @{$requirements->{required_gates}}
];
my $focused_requirements = write_file(
    File::Spec->catfile($temporary, 'notice-requirements.json'),
    JSON::PP->new->canonical->pretty->encode($requirements));

my $artifact = write_file(
    File::Spec->catfile($temporary, 'gate.artifact'),
    "retained gate evidence\n");
my $source_commit = '1' x 40;
my $manifest = {
    schema_version => 1,
    mode => 'acceptance',
    identity => {
        source_commit => $source_commit,
        perl5_commit => '2' x 40,
        runner_commit => $source_commit,
        jperl_sha256 => '3' x 64,
        jar_sha256 => $notice->{jar_sha256},
        sbom_sha256 => $notice->{sbom_sha256},
        baseline_sha256 => $baseline,
    },
    gates => {
        'notice-license' => {
            state => 'passed',
            artifact => {
                path => $artifact,
                sha256 => sha256_hex(read_file($artifact)),
            },
            identity => { source_commit => $source_commit },
            details => $notice,
        },
    },
};

my $evidence = write_file(
    File::Spec->catfile($temporary, 'acceptance.json'),
    JSON::PP->new->canonical->pretty->encode($manifest));
my $report = File::Spec->catfile($temporary, 'acceptance-report.json');
my $checker = File::Spec->catfile(
    $repository, 'dev', 'tools', 'check_phase36_acceptance_manifest.pl');
is(system($^X, $checker,
        '--requirements', $focused_requirements,
        '--evidence', $evidence,
        '--mode', 'strict',
        '--expected-commit', $source_commit,
        '--output', $report) >> 8,
    0, 'emitted notice details pass strict focused acceptance');
is(load_json($report)->{gates}{'notice-license'}{status}, 'passed',
    'strict checker classifies emitted notice details as passed');

my $mismatch = JSON::PP->new->decode(JSON::PP->new->encode($manifest));
$mismatch->{identity}{baseline_sha256} = '0' x 64;
my $mismatch_evidence = write_file(
    File::Spec->catfile($temporary, 'mismatched-baseline-acceptance.json'),
    JSON::PP->new->canonical->pretty->encode($mismatch));
my $mismatch_report = File::Spec->catfile(
    $temporary, 'mismatched-baseline-report.json');
is(system($^X, $checker,
        '--requirements', $focused_requirements,
        '--evidence', $mismatch_evidence,
        '--mode', 'report',
        '--expected-commit', $source_commit,
        '--output', $mismatch_report) >> 8,
    0, 'mismatched baseline still produces a diagnostic report');
ok(grep({ $_ eq 'evidence baseline does not match the required baseline' }
        @{load_json($mismatch_report)->{global_issues}}),
    'report mode retains the fail-closed baseline mismatch issue');

done_testing;

sub write_file {
    my ($path, $contents) = @_;
    my (undef, $directory) = File::Spec->splitpath($path);
    make_path($directory) if length($directory) && !-d $directory;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!";
    return $path;
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!";
    return $contents;
}

sub load_json {
    return JSON::PP->new->decode(read_file($_[0]));
}
