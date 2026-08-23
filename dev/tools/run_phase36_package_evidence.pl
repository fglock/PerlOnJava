#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path getcwd);
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname basename);
use File::Find qw(find);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir tempfile);
use Fcntl qw(:DEFAULT :mode);
use Getopt::Long qw(GetOptions);
use IO::Select;
use IO::Handle;
use JSON::PP;
use POSIX qw(setpgid strftime WIFEXITED WEXITSTATUS WIFSIGNALED WTERMSIG);
use Time::HiRes qw(time sleep);

use constant MAX_LOG_BYTES => 1_048_576;
use constant MAX_JSON_BYTES => 8_388_608;
use constant MAX_ARTIFACT_BYTES => 536_870_912;
use constant MAX_TREE_BYTES => 1_073_741_824;
use constant MAX_TREE_ENTRIES => 100_000;
use constant MAX_TREE_DEPTH => 64;
use constant MAX_TIMEOUT_SECONDS => 86_400;
use constant MAX_FAILURE_BYTES => 65_536;

my %limit = (
    log_bytes => MAX_LOG_BYTES, json_bytes => MAX_JSON_BYTES,
    artifact_bytes => MAX_ARTIFACT_BYTES, tree_bytes => MAX_TREE_BYTES,
    tree_entries => MAX_TREE_ENTRIES, tree_depth => MAX_TREE_DEPTH,
);

my %option = (timeout => '1800', mode => 'assertion');
my $help;
GetOptions(
    'source-root=s' => \$option{source_root},
    'expected-commit=s' => \$option{expected_commit},
    'output-root=s' => \$option{output_root},
    'make=s' => \$option{make},
    'perl=s' => \$option{perl},
    'git=s' => \$option{git},
    'dpkg-deb=s' => \$option{dpkg_deb},
    'java=s' => \$option{java},
    'jar=s' => \$option{jar},
    'mode=s' => \$option{mode},
    'timeout=s' => \$option{timeout},
    'max-log-bytes=s' => \$option{max_log_bytes},
    'max-json-bytes=s' => \$option{max_json_bytes},
    'max-artifact-bytes=s' => \$option{max_artifact_bytes},
    'max-tree-bytes=s' => \$option{max_tree_bytes},
    'max-tree-entries=s' => \$option{max_tree_entries},
    'max-tree-depth=s' => \$option{max_tree_depth},
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;
for my $name (qw(source_root expected_commit output_root make perl git dpkg_deb java jar)) {
    die "--" . ($name =~ s/_/-/gr) . " is required\n"
        unless defined $option{$name} && length $option{$name};
}
die "--expected-commit must be a full lowercase Git SHA\n"
    unless $option{expected_commit} =~ /\A[0-9a-f]{40}\z/;
die "--mode must be assertion or report\n"
    unless $option{mode} eq 'assertion' || $option{mode} eq 'report';

my $source = canonical_directory($option{source_root}, 'source root', 1);
my $output = canonical_directory($option{output_root}, 'output root', 1);
die "Source root and output root must be disjoint\n"
    if contains_path($source, $output) || contains_path($output, $source);
my @occupied = directory_entries($output);
die "Sealed output root is not empty: $output\n" if @occupied;
my $output_snapshot = directory_identity($output, 'sealed output root');
my ($handling_failure, $published_success) = (0, 0);
my (%published_bundle_records, $published_bundle_root, $bundle_published);
my ($linked_success_path, $linked_success_record, $success_staging_path);
$SIG{__DIE__} = sub {
    return if $^S || $handling_failure || $published_success;
    $handling_failure = 1;
    my $failure = $_[0];
    eval {
        if ($linked_success_path) {
            my $cleanup_error = cleanup_linked_success();
            die "$cleanup_error\n" if length $cleanup_error;
        }
        cleanup_published_bundle() if $bundle_published;
        publish_failure_notice($failure) if $option{mode} eq 'report';
    };
    if ($@) {
        my $secondary = "$@";
        $secondary =~ s/[\x00-\x08\x0b\x0c\x0e-\x1f]/?/g;
        $secondary = substr($secondary, 0, 1024);
        warn "Unable to publish durable failure notice: $secondary";
    }
    $handling_failure = 0;
};

$option{timeout} = bounded_decimal($option{timeout}, MAX_TIMEOUT_SECONDS, 'timeout');
for my $name (sort keys %limit) {
    my $option_name = 'max_' . $name;
    next unless defined $option{$option_name};
    $limit{$name} = bounded_decimal($option{$option_name}, $limit{$name},
        ($option_name =~ s/_/-/gr));
}

my $source_snapshot = directory_identity($source, 'source root');

my %tool;
for my $name (qw(make perl git dpkg_deb java jar)) {
    $tool{$name} = executable_record($option{$name}, $name);
}
die "Trusted --jar executable must retain the command name jar\n"
    unless basename($tool{jar}{path}) eq 'jar';
die "Trusted --java executable must retain the command name java\n"
    unless basename($tool{java}{path}) eq 'java';
die "Trusted java and jar must come from the same canonical installation directory\n"
    unless dirname($tool{java}{path}) eq dirname($tool{jar}{path});

my @verifier_spec = (
    [distribution => 'verify-joni-distribution.pl'],
    [packaging => 'verify-joni-packaging.pl'],
    [notice_license => 'verify_phase36_notice_license.pl'],
);
my %verifier;
for my $spec (@verifier_spec) {
    my ($name, $file) = @$spec;
    my $path = safe_source_file($source, 'dev', 'tools', $file);
    $verifier{$name} = file_record($path, "accepted $name verifier");
}
my @config_names = (
    ['Makefile'], ['build.gradle'], ['settings.gradle'],
    ['gradle', 'wrapper', 'gradle-wrapper.properties'],
);
my @configs = map {
    file_record(safe_source_file($source, @$_), 'packaging configuration')
} @config_names;
my $package_contract = parse_package_contract($configs[1]{path});
assert_make_package_contract($configs[0]{path});
my @data_names = (
    ['jperl'],
    ['src', 'main', 'java', 'org', 'perlonjava', 'core', 'Configuration.java.in'],
    ['third_party', 'joni', 'LICENSE'],
    ['third_party', 'joni', 'PERLONJAVA-NOTICE.md'],
    ['third_party', 'licenses', 'jcodings-LICENSE.txt'],
);
my @data_inputs = map {
    file_record(safe_source_file($source, @$_), 'immutable package input')
} @data_names;
my %protected = map { $_->{path} => $_ }
    (values(%tool), values(%verifier), @configs, @data_inputs);

my $work = tempdir('phase36-package-evidence-XXXXXX', TMPDIR => 1, CLEANUP => 1);
my @commands;
my %command_log_path;
my %generated_files;
my %generated_trees;
my @generated_files_required_absent;
my (%trusted_path_links, $trusted_path_directory);
my $trusted_path_snapshot;
my %base_env = (
    PATH => trusted_path(),
    LC_ALL => 'C', LANG => 'C', TZ => 'UTC',
    PERL5OPT => undef, PERL5LIB => undef,
    map { defined($ENV{$_}) ? ($_ => $ENV{$_}) : () }
        qw(HOME TMPDIR GRADLE_USER_HOME SYSTEMROOT COMSPEC PATHEXT),
    JAVA_HOME => dirname(dirname($tool{java}{path})),
    PERLONJAVA_JAVA_BIN => $tool{java}{path},
);

verify_path_resolution('perl', $tool{perl}{path}, $base_env{PATH});
verify_path_resolution('git', $tool{git}{path}, $base_env{PATH});

verify_source();
verify_protected();
reject_stale_outputs();

my $build_log = File::Spec->catfile($work, 'make-deb.log');
run_checked('make-deb', [$tool{make}{path}, '-C', $source, 'deb'], $build_log,
    \%base_env);
verify_source();
verify_protected();

my $install_parent = safe_existing_directory($source, 'build', 'install');
assert_directory_names($install_parent, ['perlonjava'], 'installDist parent');
my $install = safe_existing_directory($install_parent, 'perlonjava');
my $target = safe_existing_directory($source, 'target');
my @target_jars = grep { /\.jar\z/i } directory_entries($target);
die "Expected exact standalone JAR perlonjava-5.44.0.jar and no other JARs\n"
    unless @target_jars == 1 && $target_jars[0] eq 'perlonjava-5.44.0.jar';
my $jar = safe_existing_file($target, 'perlonjava-5.44.0.jar');
my $reports = safe_existing_directory($source, 'build', 'reports');
my $java_bom_path = File::Spec->catfile($reports, 'bom.json');
my $perl_bom_path = File::Spec->catfile($reports, 'perl-bom.json');
my $has_java_bom = (-e $java_bom_path || -l $java_bom_path) ? 1 : 0;
my $has_perl_bom = (-e $perl_bom_path || -l $perl_bom_path) ? 1 : 0;
die "Generated component BOM set is incomplete: bom.json and perl-bom.json must both exist or both be absent\n"
    if $has_java_bom != $has_perl_bom;
my $strict_package_contract = $has_java_bom ? 1 : 0;
my ($java_bom, $perl_bom);
if ($strict_package_contract) {
    $java_bom = safe_existing_file($reports, 'bom.json');
    $perl_bom = safe_existing_file($reports, 'perl-bom.json');
} else {
    @generated_files_required_absent = ($java_bom_path, $perl_bom_path);
}
my $sbom = safe_existing_file($source, 'build', 'reports', 'sbom.json');
my $distributions = safe_existing_directory($source, 'build', 'distributions');
my $deb_name = join('_', $package_contract->{package}, $package_contract->{version},
    $package_contract->{architecture}) . '.deb';
assert_directory_names($distributions, [$deb_name], 'package distributions');
my $deb = safe_existing_file($distributions, $deb_name);
%generated_files = map { $_ => file_record($_, 'generated package artifact') }
    ($jar, ($strict_package_contract ? ($java_bom, $perl_bom) : ()), $sbom, $deb);

my $install_tree = tree_record($install, {}, 'installDist');
$generated_trees{$install} = $install_tree;
my @install_jars = grep { $_->{type} eq 'file' && $_->{path} =~ m{\Alib/[^/]+\.jar\z} }
    @{$install_tree->{entries}};
die "installDist must contain exactly one runtime JAR\n" unless @install_jars == 1;
my $installed_jar = safe_existing_file($install, split m{/}, $install_jars[0]{path});
assert_same_file($jar, $installed_jar, 'standalone and installDist JAR');
my $sbom_relation = $strict_package_contract
    ? assert_sbom_relation($java_bom, $perl_bom, $sbom,
        $option{expected_commit})
    : assert_legacy_sbom_commit($sbom, $option{expected_commit});
my $version_log = File::Spec->catfile($work, 'jar-version.log');
run_checked('jar-version', [$tool{java}{path}, '-cp', $jar,
        'org.perlonjava.app.cli.Main', '-V:git_commit_id'],
    $version_log, \%base_env);
my $version_output = read_bounded($version_log, $limit{log_bytes}, 'JAR version output');
die "Produced JAR version output is malformed\n"
    unless $version_output =~ /\Agit_commit_id='([0-9a-f]{7,40})';\n\z/;
my $reported_commit = $1;
my $resolved_log = File::Spec->catfile($work, 'jar-commit-resolve.log');
run_checked('jar-commit-resolve', [$tool{git}{path}, '-C', $source,
        'rev-parse', '--verify', "$reported_commit^{commit}"],
    $resolved_log, \%base_env);
my $resolved_commit = read_bounded($resolved_log, $limit{log_bytes},
    'resolved JAR source commit');
$resolved_commit =~ s/\s+\z//;
die "Produced JAR does not bind to the expected full source commit\n"
    unless $resolved_commit eq $option{expected_commit};

my $notice_output = File::Spec->catfile($work, 'notice-license.json');
run_checked('verify-distribution',
    [$tool{perl}{path}, $verifier{distribution}{path}, $install],
    File::Spec->catfile($work, 'verify-distribution.log'), \%base_env);
run_checked('verify-packaging',
    [$tool{perl}{path}, $verifier{packaging}{path}, '--strict', $jar, $sbom],
    File::Spec->catfile($work, 'verify-packaging.log'), \%base_env);
run_checked('verify-notice-license',
    [$tool{perl}{path}, $verifier{notice_license}{path}, '--strict',
        '--source-root', $source, '--jar', $jar, '--sbom', $sbom,
        '--output', $notice_output],
    File::Spec->catfile($work, 'verify-notice-license.log'), \%base_env);
my $notice = load_json($notice_output, 'notice/license verifier output');
if ($strict_package_contract) {
    assert_exact_keys($notice, 'notice/license verifier output', qw(schema_version
        kind verified missing_notices changed_notices missing_licenses
        changed_licenses jar_path jar_sha256 sbom_path sbom_sha256 source_root
        notices components relationships));
    die "Notice/license verifier schema or kind mismatch\n"
        unless ($notice->{schema_version} // 0) == 1
            && ($notice->{kind} // '') eq 'notice-license';
} else {
    assert_allowed_keys($notice, 'legacy notice/license verifier output',
        qw(verified jar_sha256 sbom_sha256 padding));
    die "Legacy notice/license verifier output is missing required fields\n"
        unless exists($notice->{verified}) && exists($notice->{jar_sha256})
            && exists($notice->{sbom_sha256});
}
die "Notice/license verifier did not report success\n" unless $notice->{verified};
die "Notice/license verifier JAR identity mismatch\n"
    unless ($notice->{jar_sha256} // '') eq sha256_file($jar);
die "Notice/license verifier SBOM identity mismatch\n"
    unless ($notice->{sbom_sha256} // '') eq sha256_file($sbom);
verify_source();
verify_protected();

my $deb_info = File::Spec->catfile($work, 'dpkg-control.log');
run_checked('dpkg-control', [$tool{dpkg_deb}{path}, '--field', $deb], $deb_info,
    \%base_env);
my $control = parse_debian_control(
    read_bounded($deb_info, $limit{log_bytes}, 'Debian control metadata'));
for my $field (qw(Package Version Architecture Maintainer)) {
    my $key = lc $field;
    die "Debian control $field mismatch\n"
        unless ($control->{$field} // '') eq $package_contract->{$key};
}
my $deb_contents = File::Spec->catfile($work, 'dpkg-contents.log');
run_checked('dpkg-contents', [$tool{dpkg_deb}{path}, '--contents', $deb],
    $deb_contents, \%base_env);
assert_safe_dpkg_listing(read_bounded($deb_contents, $limit{log_bytes},
    'Debian contents listing'));
my $extract = File::Spec->catdir($work, 'deb-root');
make_path($extract);
$extract = abs_path($extract) or die "Cannot resolve private Debian extraction root\n";
run_checked('dpkg-extract', [$tool{dpkg_deb}{path}, '--extract', $deb, $extract],
    File::Spec->catfile($work, 'dpkg-extract.log'), \%base_env);
my %allowed_links = (
    'usr/local/bin/jperl' => '/opt/perlonjava/bin/jperl',
    'usr/local/bin/jcpan' => '/opt/perlonjava/bin/jcpan',
    'usr/local/bin/jperldoc' => '/opt/perlonjava/bin/jperldoc',
    'usr/local/bin/jprove' => '/opt/perlonjava/bin/jprove',
);
my $deb_tree = tree_record($extract, \%allowed_links, 'Debian package');
my $package_root = safe_existing_directory($extract, 'opt', 'perlonjava');
my $deb_jar = safe_existing_file($package_root, 'lib', basename($jar));
my $deb_sbom = safe_existing_file($package_root, 'share', 'sbom', 'sbom.json');
assert_same_file($jar, $deb_jar, 'standalone and Debian JAR');
assert_same_file($sbom, $deb_sbom, 'external and Debian SBOM');
for my $name (qw(joni-LICENSE.txt joni-PERLONJAVA-NOTICE.md jcodings-LICENSE.txt)) {
    assert_same_file(
        safe_existing_file($install, 'share', 'licenses', $name),
        safe_existing_file($package_root, 'share', 'licenses', $name),
        "installDist and Debian notice $name");
}
assert_exact_debian_payload($install_tree, $deb_tree, \%allowed_links, $sbom);
my $final_install_tree = tree_record($install, {}, 'final installDist');
die "installDist mutated during package verification\n"
    unless $final_install_tree->{tree_sha256} eq $install_tree->{tree_sha256}
        && $final_install_tree->{identity_sha256} eq $install_tree->{identity_sha256};
verify_source();
verify_protected();

my @artifacts = map { file_record($_->[0], $_->[1]) } (
    [$jar, 'standalone JAR'],
    ($strict_package_contract
        ? ([$java_bom, 'Java BOM'], [$perl_bom, 'Perl BOM']) : ()),
    [$sbom, 'merged SBOM'], [$deb, 'Debian package'],
);
my $report = {
    schema_version => 1,
    kind => 'phase36-package-evidence-report',
    producer => 'run_phase36_package_evidence.pl',
    mode => $option{mode},
    authoritative => JSON::PP::false,
    status => 'pass',
    verified => JSON::PP::true,
    missing_entries => 0,
    duplicate_entries => 0,
    jar_sha256 => sha256_file($jar),
    sbom_sha256 => sha256_file($sbom),
    identity => {
        source_root => $source,
        source_commit => $option{expected_commit},
        jar_sha256 => sha256_file($jar),
        sbom_sha256 => sha256_file($sbom),
    },
    build_contract => {
        make_target => 'deb',
        install_dist_via_build_deb_dependency => JSON::PP::true,
        timeout_seconds => $option{timeout},
        environment => \%base_env,
        environment_sha256 => sha256_hex(canonical(\%base_env)),
        resource_limits => { %limit },
    },
    tools => { map { $_ => $tool{$_} } sort keys %tool },
    verifiers => { map { $_ => $verifier{$_} } sort keys %verifier },
    configs => \@configs,
    immutable_inputs => \@data_inputs,
    package => {
        %$package_contract,
        filename => $deb_name,
        control => $control,
        verified => JSON::PP::true,
        missing_entries => 0,
        duplicate_entries => 0,
    },
    commands => \@commands,
    artifacts => \@artifacts,
    sbom_relation => $sbom_relation,
    trees => { install_dist => $install_tree, debian => $deb_tree },
    notice_license => $notice,
    notice_license_artifact => {
        verified => JSON::PP::true,
        sha256 => sha256_file($notice_output),
        size => -s $notice_output,
    },
};
verify_source();
verify_protected();
verify_output_root(0);
if (!$strict_package_contract) {
    assert_exact_keys($report, 'legacy package evidence report', qw(schema_version
        kind producer mode authoritative status verified missing_entries
        duplicate_entries jar_sha256 sbom_sha256 identity build_contract tools
        verifiers configs immutable_inputs package commands artifacts
        sbom_relation trees notice_license notice_license_artifact));
    die "Legacy package evidence report must be explicitly non-authoritative\n"
        if $report->{authoritative} || exists $report->{completion}
            || $report->{kind} ne 'phase36-package-evidence-report';
    publish_atomic(File::Spec->catfile($output, 'package-evidence.json'),
        canonical_pretty($report), 0);
    $published_success = 1;
    {
        local $SIG{PIPE} = 'IGNORE';
        print File::Spec->catfile($output, 'package-evidence.json'), "\n";
    }
    exit 0;
}
my $retained = publish_evidence_bundle($report, {
    jar => $jar, java_bom => $java_bom, perl_bom => $perl_bom,
    sbom => $sbom, deb => $deb, notice_license => $notice_output,
});
my $document = {
    schema_version => 1,
    kind => 'packaging',
    producer => 'run_phase36_package_evidence.pl',
    verified => JSON::PP::true,
    identity => {
        source_commit => $option{expected_commit},
        jar_sha256 => sha256_file($jar),
        sbom_sha256 => sha256_file($sbom),
    },
    completion => {
        exit_code => 0, signal => 0, timeout => JSON::PP::false,
        incomplete => JSON::PP::false, review_stop => JSON::PP::false,
    },
    artifacts => $retained,
    missing_entries => 0,
    duplicate_entries => 0,
};
assert_exact_keys($document, 'final packaging bridge', qw(schema_version kind
    producer verified identity completion artifacts missing_entries
    duplicate_entries));
assert_exact_keys($document->{identity}, 'final packaging identity',
    qw(source_commit jar_sha256 sbom_sha256));
assert_exact_keys($document->{completion}, 'final packaging completion',
    qw(exit_code signal timeout incomplete review_stop));
publish_atomic(File::Spec->catfile($output, 'package-evidence.json'),
    canonical_pretty($document), 1);
$published_success = 1;
{
    local $SIG{PIPE} = 'IGNORE';
    print File::Spec->catfile($output, 'package-evidence.json'), "\n";
}
exit 0;

sub run_checked {
    my ($name, $argv, $log, $environment) = @_;
    verify_tools();
    verify_protected();
    verify_generated_files();
    verify_output_root(0);
    my $result = run_child($argv, $log, $option{timeout}, $environment);
    verify_tools();
    verify_protected();
    verify_generated_files();
    verify_output_root(0);
    my $bytes = read_bounded($log, $limit{log_bytes}, "$name log");
    my $record = {
        name => $name, argv => $argv, argv_sha256 => sha256_hex(canonical($argv)),
        log_sha256 => sha256_hex($bytes), log_size => length($bytes),
        started_at => $result->{started_at}, ended_at => $result->{ended_at},
        duration_seconds => $result->{duration_seconds},
        exit_code => $result->{exit_code}, signal => $result->{signal},
        timeout => $result->{timeout} ? JSON::PP::true : JSON::PP::false,
    };
    push @commands, $record;
    $command_log_path{$#commands} = $log;
    die "$name exceeded the command log byte limit\n" if $result->{log_overflow};
    die "$name timed out after $option{timeout} seconds\n" if $result->{timeout};
    die "$name terminated by signal $result->{signal}\n" if $result->{signal};
    die "$name exited nonzero ($result->{exit_code})\n" if $result->{exit_code};
    return $record;
}

sub run_child {
    my ($argv, $log, $timeout, $environment) = @_;
    my $started = time;
    pipe my $reader, my $writer or die "Cannot create command log pipe: $!\n";
    my $pid = fork();
    die "Cannot fork: $!\n" unless defined $pid;
    if (!$pid) {
        close $reader;
        eval { setpgid(0, 0) };
        open STDIN, '<', File::Spec->devnull or die "Cannot redirect stdin: $!\n";
        open STDOUT, '>&', $writer or die "Cannot redirect stdout: $!\n";
        open STDERR, '>&', \*STDOUT or die "Cannot redirect stderr: $!\n";
        close $writer;
        %ENV = map { defined $environment->{$_} ? ($_ => $environment->{$_}) : () }
            keys %$environment;
        exec { $argv->[0] } @$argv;
        die "Cannot execute $argv->[0]: $!\n";
    }
    close $writer;
    eval { setpgid($pid, $pid) };
    sysopen my $log_fh, $log, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot exclusively create command log $log: $!\n";
    binmode $log_fh, ':raw';
    my $select = IO::Select->new($reader);
    my ($status, $timed_out, $overflow, $reaped, $eof, $bytes) = (undef, 0, 0, 0, 0, 0);
    while (!$reaped || !$eof) {
        for my $ready ($select->can_read(0.05)) {
            my $chunk = '';
            my $count = sysread($ready, $chunk, 65_536);
            die "Cannot read command output: $!\n" unless defined $count;
            if (!$count) { $select->remove($ready); $eof = 1; next }
            if ($bytes + $count > $limit{log_bytes}) {
                $overflow = 1;
                kill 'KILL', -$pid;
                next;
            }
            print {$log_fh} $chunk or die "Cannot write command log: $!\n";
            $bytes += $count;
        }
        if (!$timed_out && !$overflow && time - $started >= $timeout) {
            $timed_out = 1;
            kill 'KILL', -$pid;
        }
        next if $reaped;
        my $waited = waitpid($pid, POSIX::WNOHANG());
        if ($waited == $pid) { $status = $?; $reaped = 1; next }
        die "waitpid failed: $!\n" if $waited < 0;
    }
    close $reader or die "Cannot close command output pipe: $!\n";
    close $log_fh or die "Cannot close command log: $!\n";
    my $ended = time;
    return {
        started_at => timestamp($started), ended_at => timestamp($ended),
        duration_seconds => 0 + sprintf('%.6f', $ended - $started),
        timeout => $timed_out ? 1 : 0,
        log_overflow => $overflow ? 1 : 0,
        exit_code => WIFEXITED($status) ? WEXITSTATUS($status) : 0,
        signal => WIFSIGNALED($status) ? WTERMSIG($status) : 0,
    };
}

sub verify_source {
    verify_directory_identity($source_snapshot, 'source root');
    my $head_log = File::Spec->catfile($work, 'git-head-' . scalar(@commands) . '.log');
    my $head = run_checked('git-head', [$tool{git}{path}, '-C', $source,
        'rev-parse', 'HEAD'], $head_log, \%base_env);
    my $actual = read_bounded($head_log, $limit{log_bytes}, 'git HEAD output');
    $actual =~ s/\s+\z//;
    die "Source commit mismatch: expected $option{expected_commit}, got $actual\n"
        unless $actual eq $option{expected_commit};
    my $status_log = File::Spec->catfile($work, 'git-status-' . scalar(@commands) . '.log');
    run_checked('git-status', [$tool{git}{path}, '-C', $source, 'status',
        '--porcelain=v1', '--untracked-files=all'], $status_log, \%base_env);
    die "Source tree is dirty or mutated\n"
        if length read_bounded($status_log, $limit{log_bytes}, 'git status output');
}

sub reject_stale_outputs {
    my @paths = (
        File::Spec->catdir($source, 'build', 'install'),
        File::Spec->catdir($source, 'build', 'distributions'),
        File::Spec->catdir($source, 'build', 'packages'),
        File::Spec->catdir($source, 'build', 'debian'),
        File::Spec->catfile($source, 'build', 'reports', 'sbom.json'),
        File::Spec->catfile($source, 'build', 'reports', 'bom.json'),
        File::Spec->catfile($source, 'build', 'reports', 'perl-bom.json'),
        File::Spec->catfile($source, 'build', 'reports', 'cyclonedx', 'bom.json'),
        File::Spec->catfile($source, 'build', 'reports', 'cyclonedx', 'perl-bom.json'),
        File::Spec->catfile($source, 'build', 'reports', 'cyclonedx', 'sbom.json'),
    );
    for my $dir (File::Spec->catdir($source, 'target'),
            File::Spec->catdir($source, 'build')) {
        next unless -d $dir;
        my $seen = 0;
        find({ no_chdir => 1, follow => 0, wanted => sub {
            die "Stale-output scan exceeded entry limit\n"
                if ++$seen > $limit{tree_entries};
            return if -d $_;
            my $name = lc basename($_);
            push @paths, $_ if $name =~ /\A(?:bom|perl-bom|sbom)\.json\z/
                || ($dir =~ /target\z/ && $name =~ /\Aperlonjava-.*\.jar\z/)
                || $name =~ /\.deb\z/;
        }}, $dir);
    }
    my @stale = grep { -e $_ || -l $_ } @paths;
    die "Stale preexisting package output: $stale[0]\n" if @stale;
}

sub verify_protected {
    for my $path (sort keys %protected) {
        verify_file_record($protected{$path}, 'Protected tool/config');
    }
}

sub verify_tools {
    for my $name (sort keys %tool) {
        my $path = $tool{$name}{path};
        verify_file_record($tool{$name}, "Trusted $name executable");
        die "Trusted $name executable lost execute permission: $path\n" unless -x $path;
        my $resolved = abs_path($tool{$name}{requested_path});
        die "Trusted $name requested location changed\n"
            unless defined $resolved && $resolved eq $path;
    }
    verify_path_resolution('perl', $tool{perl}{path}, $base_env{PATH});
    verify_path_resolution('git', $tool{git}{path}, $base_env{PATH});
    for my $name (sort keys %trusted_path_links) {
        my $link = File::Spec->catfile($trusted_path_directory, $name);
        die "Trusted PATH link changed: $link\n"
            unless -l $link && (readlink($link) // '') eq $trusted_path_links{$name};
    }
    assert_directory_names($trusted_path_directory,
        [sort keys %trusted_path_links], 'trusted PATH directory');
    verify_directory_identity($trusted_path_snapshot, 'trusted PATH directory');
}

sub verify_generated_files {
    for my $path (sort keys %generated_files) {
        verify_file_record($generated_files{$path}, 'Generated package artifact');
    }
    for my $path (sort keys %generated_trees) {
        my $current = tree_record($path, {}, 'generated installDist snapshot');
        die "Generated package tree mutated: $path\n"
            unless $current->{tree_sha256} eq $generated_trees{$path}{tree_sha256}
                && $current->{identity_sha256}
                    eq $generated_trees{$path}{identity_sha256};
    }
    for my $path (@generated_files_required_absent) {
        die "Legacy component BOM absence changed after contract selection: $path\n"
            if -e $path || -l $path;
    }
}

sub assert_sbom_relation {
    my ($java_path, $perl_path, $merged_path, $expected) = @_;
    my $java = load_json($java_path, 'Java BOM');
    my $perl = load_json($perl_path, 'Perl BOM');
    my $doc = load_json($merged_path, 'merged SBOM');
    my @sbom_fields = qw($schema bomFormat specVersion serialNumber version
        metadata components dependencies);
    assert_allowed_keys($java, 'Java BOM', @sbom_fields);
    assert_allowed_keys($perl, 'Perl BOM', @sbom_fields);
    assert_exact_keys($doc, 'merged SBOM', @sbom_fields);
    for my $item ([$java, 'Java BOM'], [$perl, 'Perl BOM'], [$doc, 'merged SBOM']) {
        die "$item->[1] is not a CycloneDX 1.6 object\n"
            unless ($item->[0]{bomFormat} // '') eq 'CycloneDX'
                && ($item->[0]{specVersion} // '') eq '1.6'
                && ref($item->[0]{metadata}) eq 'HASH'
                && ref($item->[0]{components}) eq 'ARRAY'
                && ref($item->[0]{dependencies}) eq 'ARRAY';
    }
    my @expected_components = (
        @{$java->{components}},
        (grep { ref($_) eq 'HASH'
            && ($_->{group} // '') eq 'org.perlonjava.fork'
            && ($_->{name} // '') eq 'joni-fork' } @{$doc->{components}}),
        @{$perl->{components}},
    );
    die "Merged SBOM component relation to bom.json and perl-bom.json is not exact\n"
        unless @expected_components == @{$java->{components}} + @{$perl->{components}} + 1
            && canonical(\@expected_components) eq canonical($doc->{components});
    my @values;
    for my $component (@{$doc->{components} // []}) {
        next unless ref($component) eq 'HASH';
        next unless ($component->{group} // '') eq 'org.perlonjava.fork'
            && ($component->{name} // '') eq 'joni-fork';
        push @values, map { ref($_) eq 'HASH'
            && ($_->{name} // '') eq 'perlonjava:source-commit'
            ? ($_->{value} // '') : () } @{$component->{properties} // []};
    }
    die "Merged SBOM must bind exactly one Joni source commit\n" unless @values == 1;
    die "Merged SBOM source commit mismatch\n" unless $values[0] eq $expected;
    my @root_refs = (
        (map { ref($_) eq 'HASH' && length($_->{'bom-ref'} // '')
            ? $_->{'bom-ref'} : () } @{$java->{components}}),
        'pkg:generic/perlonjava/joni-fork@2.2.7',
        (map { ref($_) eq 'HASH' && length($_->{'bom-ref'} // '')
            ? $_->{'bom-ref'} : () } @{$perl->{components}}),
    );
    die "Merged SBOM dependency relation is not schema-locked to its input BOMs\n"
        unless @{$doc->{dependencies}} == 2
            && ref($doc->{dependencies}[0]) eq 'HASH'
            && canonical($doc->{dependencies}[0]) eq canonical({
                ref => 'perlonjava', dependsOn => \@root_refs })
            && ref($doc->{dependencies}[1]) eq 'HASH'
            && canonical($doc->{dependencies}[1]) eq canonical({
                ref => 'pkg:generic/perlonjava/joni-fork@2.2.7',
                dependsOn => ['pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar'],
            });
    return {
        java_bom_sha256 => sha256_file($java_path),
        perl_bom_sha256 => sha256_file($perl_path),
        sbom_sha256 => sha256_file($merged_path),
        relation => 'java-components+joni-fork+perl-components',
        verified => JSON::PP::true,
    };
}
sub assert_legacy_sbom_commit {
    my ($path, $expected) = @_;
    my $doc = load_json($path, 'legacy merged SBOM');
    assert_exact_keys($doc, 'legacy merged SBOM', qw(bomFormat components));
    die "Legacy merged SBOM is not CycloneDX\n"
        unless ($doc->{bomFormat} // '') eq 'CycloneDX'
            && ref($doc->{components}) eq 'ARRAY';
    my @values;
    for my $component (@{$doc->{components}}) {
        next unless ref($component) eq 'HASH';
        next unless ($component->{group} // '') eq 'org.perlonjava.fork'
            && ($component->{name} // '') eq 'joni-fork';
        push @values, map { ref($_) eq 'HASH'
            && ($_->{name} // '') eq 'perlonjava:source-commit'
            ? ($_->{value} // '') : () } @{$component->{properties} // []};
    }
    die "Legacy merged SBOM must bind exactly one Joni source commit\n"
        unless @values == 1;
    die "Legacy merged SBOM source commit mismatch\n"
        unless $values[0] eq $expected;
    return {
        sbom_sha256 => sha256_file($path),
        relation => 'legacy-merged-sbom-only',
        authoritative => JSON::PP::false,
        verified => JSON::PP::true,
    };
}

sub assert_safe_dpkg_listing {
    my ($text) = @_;
    die "dpkg-deb contents listing is empty or malformed\n" unless $text =~ /\S/;
    my (%seen, %casefold);
    for my $line (split /\n/, $text) {
        next unless $line =~ /\S/;
        die "dpkg-deb contents listing has malformed line\n"
            unless $line =~ /\s(\.\/\S.*)\z/;
        my $path = $1; $path =~ s/\s+->\s+.*\z//;
        die "Debian package contains unsafe path $path\n"
            if $path =~ /(?:\A|\/)\.\.(?:\/|\z)/ || $path =~ m{\A/};
        die "Debian package listing contains duplicate path $path\n" if $seen{$path}++;
        my $folded = lc $path;
        die "Debian package listing contains case-fold collision at $path\n"
            if exists $casefold{$folded} && $casefold{$folded} ne $path;
        $casefold{$folded} = $path;
    }
}

sub tree_record {
    my ($root, $allowed_links, $label) = @_;
    my @entries;
    my @identities;
    my (%casefold, $total_bytes);
    my @root_stat = lstat($root);
    die "Cannot lstat $label root: $!\n"
        unless @root_stat && S_ISDIR($root_stat[2]);
    find({ no_chdir => 1, follow => 0, wanted => sub {
        return if $_ eq $root;
        my $relative = File::Spec->abs2rel($_, $root); $relative =~ s{\\}{/}g;
        die "$label contains path escape $relative\n"
            if $relative =~ /(?:\A|\/)\.\.(?:\/|\z)/;
        die "$label exceeds tree depth limit at $relative\n"
            if scalar(split m{/}, $relative) > $limit{tree_depth};
        die "$label exceeds tree entry limit\n"
            if @entries >= $limit{tree_entries};
        my $folded = lc $relative;
        die "$label contains case-fold collision at $relative\n"
            if exists $casefold{$folded} && $casefold{$folded} ne $relative;
        $casefold{$folded} = $relative;
        my @stat = lstat($_); die "Cannot lstat $_: $!\n" unless @stat;
        push @identities, { path => $relative, device => 0 + $stat[0],
            inode => 0 + $stat[1], mode => 0 + $stat[2], size => 0 + $stat[7] };
        my $mode = $stat[2];
        my $entry = { path => $relative, mode => sprintf('%04o', S_IMODE($mode)) };
        if (S_ISLNK($mode)) {
            my $target = readlink($_); die "Cannot read symlink $_: $!\n" unless defined $target;
            die "$label contains unexpected symlink $relative -> $target\n"
                unless exists $allowed_links->{$relative} && $allowed_links->{$relative} eq $target;
            $entry->{type} = 'symlink'; $entry->{target} = $target;
        } elsif (S_ISDIR($mode)) { $entry->{type} = 'directory' }
        elsif (S_ISREG($mode)) {
            $entry->{type} = 'file'; $entry->{size} = $stat[7];
            die "$label contains oversized artifact $relative\n"
                if $stat[7] > $limit{artifact_bytes};
            $total_bytes += $stat[7];
            die "$label exceeds tree byte limit\n"
                if $total_bytes > $limit{tree_bytes};
            $entry->{sha256} = sha256_file($_);
        } else { die "$label contains unsupported file type $relative\n" }
        push @entries, $entry;
    }}, $root);
    @entries = sort { $a->{path} cmp $b->{path} } @entries;
    @identities = sort { $a->{path} cmp $b->{path} } @identities;
    return { root => $root, entries => \@entries, entry_count => scalar(@entries),
        total_file_bytes => $total_bytes,
        identity_sha256 => sha256_hex(canonical({
            root => [0 + $root_stat[0], 0 + $root_stat[1], 0 + $root_stat[2]],
            entries => \@identities,
        })),
        tree_sha256 => sha256_hex(canonical(\@entries)) };
}

sub assert_tree_subset_equal {
    my ($expected_root, $actual_root) = @_;
    my $expected = tree_record($expected_root, {}, 'installDist comparison');
    my $actual = tree_record($actual_root, {}, 'Debian installDist comparison');
    die "Debian /opt/perlonjava payload is not exactly equal to installDist\n"
        unless canonical($expected->{entries}) eq canonical($actual->{entries});
}

sub assert_exact_debian_payload {
    my ($install_tree, $debian_tree, $links, $sbom_path) = @_;
    my %expected = map { $_ => 1 } qw(opt opt/perlonjava usr usr/local usr/local/bin);
    for my $entry (@{$install_tree->{entries}}) {
        my $mapped = 'opt/perlonjava/' . $entry->{path};
        $expected{$mapped} = 1;
        my %wanted = %$entry; $wanted{path} = $mapped;
        my ($actual_entry) = grep { $_->{path} eq $mapped } @{$debian_tree->{entries}};
        die "Debian payload is missing installDist entry $entry->{path}\n"
            unless $actual_entry;
        die "Debian payload differs from installDist at $entry->{path}\n"
            unless canonical(\%wanted) eq canonical($actual_entry);
    }
    # build.gradle adds the merged SBOM to the Debian payload separately from
    # installDist. It is the only configured package-only regular file.
    $expected{'opt/perlonjava/share'} = 1;
    $expected{'opt/perlonjava/share/sbom'} = 1;
    $expected{'opt/perlonjava/share/sbom/sbom.json'} = 1;
    $expected{$_} = 1 for keys %$links;
    my %actual = map { $_->{path} => $_ } @{$debian_tree->{entries}};
    my @missing = sort grep { !exists $actual{$_} } keys %expected;
    my @extra = sort grep { !exists $expected{$_} } keys %actual;
    die "Debian payload missing entries: " . join(', ', @missing) . "\n" if @missing;
    die "Debian payload has unexpected entries: " . join(', ', @extra) . "\n" if @extra;
    for my $path (sort keys %$links) {
        my $entry = $actual{$path};
        die "Debian payload link $path is not the required symlink\n"
            unless $entry->{type} eq 'symlink' && $entry->{target} eq $links->{$path};
    }
    my $sbom = $actual{'opt/perlonjava/share/sbom/sbom.json'};
    die "Debian payload SBOM is not the exact configured merged SBOM\n"
        unless $sbom->{type} eq 'file' && $sbom->{sha256} eq sha256_file($sbom_path);
}

sub assert_same_file {
    my ($a, $b, $label) = @_;
    die "$label bytes differ\n" unless sha256_file($a) eq sha256_file($b);
}

sub executable_record {
    my ($path, $label) = @_;
    die "Trusted $label executable must be an absolute path\n"
        unless File::Spec->file_name_is_absolute($path);
    my $canonical = abs_path($path) or die "Cannot resolve trusted $label executable\n";
    die "Trusted $label executable path must itself be canonical\n"
        unless File::Spec->canonpath($path) eq $canonical;
    die "Trusted $label executable is not a regular executable file\n"
        unless -f $canonical && -x $canonical;
    my $record = file_record($canonical, "trusted $label executable");
    $record->{requested_path} = $path;
    return $record;
}

sub file_record {
    my ($path, $label) = @_;
    die "$label is missing or empty: $path\n" unless -f $path && -s $path;
    my @stat = stat($path);
    die "$label exceeds artifact byte limit: $path\n"
        if $stat[7] > $limit{artifact_bytes};
    return { path => $path, sha256 => sha256_file($path), size => $stat[7],
        device => 0 + $stat[0], inode => 0 + $stat[1],
        mode => sprintf('%04o', S_IMODE($stat[2])) };
}

sub verify_file_record {
    my ($record, $label) = @_;
    my $path = $record->{path};
    die "$label disappeared: $path\n" unless -f $path;
    my $canonical = abs_path($path);
    die "$label canonical location changed: $path\n"
        unless defined $canonical && $canonical eq $path;
    my @stat = stat($path);
    die "$label identity changed: $path\n"
        unless $stat[0] == $record->{device} && $stat[1] == $record->{inode}
            && sprintf('%04o', S_IMODE($stat[2])) eq $record->{mode}
            && $stat[7] == $record->{size};
    die "$label mutated: $path\n" unless sha256_file($path) eq $record->{sha256};
}

sub safe_source_file { return safe_existing_file(shift, @_) }
sub safe_existing_file {
    my ($root, @parts) = @_;
    my $path = File::Spec->catfile($root, @parts);
    my $canonical = abs_path($path) or die "Missing file $path\n";
    die "Path escapes root: $path\n" unless contains_path($root, $canonical);
    die "Expected canonical nonsymlink regular file: $path\n"
        unless File::Spec->canonpath($path) eq $canonical && !-l $path && -f $canonical;
    return $canonical;
}
sub safe_existing_directory {
    my ($root, @parts) = @_;
    my $path = File::Spec->catdir($root, @parts);
    my $canonical = abs_path($path) or die "Missing directory $path\n";
    die "Path escapes root: $path\n" unless contains_path($root, $canonical);
    die "Expected canonical nonsymlink directory: $path\n"
        unless File::Spec->canonpath($path) eq $canonical && !-l $path && -d $canonical;
    return $canonical;
}
sub canonical_directory {
    my ($path, $label, $require_exact) = @_;
    die "$label must be an absolute path\n" unless File::Spec->file_name_is_absolute($path);
    my $canonical = abs_path($path) or die "Cannot resolve $label $path\n";
    die "$label is not a directory\n" unless -d $canonical;
    die "$label must be canonical (no symlink or dot components)\n"
        if $require_exact && File::Spec->canonpath($path) ne $canonical;
    return $canonical;
}
sub contains_path {
    my ($root, $path) = @_;
    my $separator = $^O eq 'MSWin32' ? '\\' : '/';
    return $path eq $root || index($path, $root . $separator) == 0;
}
sub directory_entries {
    my ($dir) = @_;
    opendir my $fh, $dir or die "Cannot open directory $dir: $!\n";
    my @entries = sort grep { $_ ne '.' && $_ ne '..' } readdir $fh;
    closedir $fh or die "Cannot close directory $dir: $!\n";
    return @entries;
}
sub assert_directory_names {
    my ($dir, $expected, $label) = @_;
    my @actual = directory_entries($dir);
    my %folded;
    for my $name (@actual) {
        die "$label has case-fold collision at $name\n"
            if exists $folded{lc $name} && $folded{lc $name} ne $name;
        $folded{lc $name} = $name;
    }
    die "$label has unexpected names: " . join(', ', @actual) . "\n"
        unless canonical(\@actual) eq canonical([sort @$expected]);
}
sub parse_package_contract {
    my ($path) = @_;
    my $text = read_bounded($path, 1_048_576, 'production package configuration');
    my @versions = $text =~ /^version\s*=\s*'([^']+)'\s*$/mg;
    my @packages = $text =~ /^\s*packageName\s*=\s*'([^']+)'\s*$/mg;
    my @maintainers = $text =~ /^\s*maintainer\s*=\s*'([^']+)'\s*$/mg;
    my @architectures = $text =~ /^\s*architecture\s*=\s*(\S+)\s*$/mg;
    die "Production package configuration must define one literal project version\n"
        unless @versions == 1;
    die "Production package configuration must define one packageName\n"
        unless @packages == 1;
    die "Production package configuration must define one maintainer\n"
        unless @maintainers == 1;
    die "Production package configuration has unsupported architecture metadata\n"
        unless @architectures == 0
            || (@architectures == 1 && $architectures[0] eq 'NOARCH');
    die "Production package name/version must be exactly perlonjava/5.44.0\n"
        unless $packages[0] eq 'perlonjava' && $versions[0] eq '5.44.0';
    return { package => $packages[0], version => $versions[0], architecture => 'all',
        maintainer => $maintainers[0] };
}
sub assert_make_package_contract {
    my ($path) = @_;
    my $text = read_bounded($path, 1_048_576, 'production Make package configuration');
    die "Production Makefile does not expose bounded deb -> buildDeb packaging\n"
        unless $text =~ /^deb:\s*check-java-gradle\s*$/m
            && $text =~ /^\s*gradlew\.bat\s+buildDeb\s*$/m
            && $text =~ /^\s*\.\/gradlew\s+buildDeb\s*$/m;
}
sub parse_debian_control {
    my ($text) = @_;
    die "Debian control metadata is empty\n" unless $text =~ /\S/;
    my (%fields, $current);
    for my $line (split /\n/, $text, -1) {
        $line =~ s/\r\z//;
        next if $line eq '';
        if ($line =~ /^([A-Za-z0-9][A-Za-z0-9-]*):[ \t]*(.*)\z/) {
            die "Debian control has duplicate field $1\n" if exists $fields{$1};
            $fields{$1} = $2; $current = $1;
        } elsif ($line =~ /^[ \t](.*)\z/ && defined $current) {
            $fields{$current} .= "\n$1";
        } else {
            die "Malformed Debian control metadata line\n";
        }
    }
    for my $field (qw(Package Version Architecture Maintainer)) {
        die "Debian control is missing $field\n" unless exists $fields{$field};
    }
    return \%fields;
}
sub trusted_path {
    my $separator = $^O eq 'MSWin32' ? ';' : ':';
    $trusted_path_directory = File::Spec->catdir($work, 'trusted-bin');
    make_path($trusted_path_directory);
    for my $name (qw(perl git java jar)) {
        my $target = $tool{$name}{path};
        my $link = File::Spec->catfile($trusted_path_directory, $name);
        symlink($target, $link) or die "Cannot create trusted PATH link $link: $!\n";
        $trusted_path_links{$name} = $target;
    }
    $trusted_path_snapshot = directory_identity($trusted_path_directory,
        'trusted PATH directory');
    my (@parts, %seen);
    for my $dir ($trusted_path_directory,
            dirname($tool{make}{path}), dirname($tool{dpkg_deb}{path}),
            split(/\Q$separator\E/, $ENV{PATH} // '')) {
        next unless length $dir;
        my $canonical = abs_path($dir) // next;
        push @parts, $canonical unless $seen{$canonical}++;
    }
    return join($separator, @parts);
}
sub verify_path_resolution {
    my ($name, $expected, $path) = @_;
    my $separator = $^O eq 'MSWin32' ? ';' : ':';
    my $resolved;
    for my $dir (split /\Q$separator\E/, $path) {
        my $candidate = File::Spec->catfile($dir, $name);
        if (-f $candidate && -x $candidate) { $resolved = abs_path($candidate); last }
    }
    die "Trusted PATH does not resolve bare $name to its accepted executable\n"
        unless defined $resolved && $resolved eq $expected;
}
sub directory_identity {
    my ($path, $label) = @_;
    my @stat = lstat($path);
    die "Cannot stat $label: $!\n" unless @stat && S_ISDIR($stat[2]);
    return { path => $path, device => 0 + $stat[0], inode => 0 + $stat[1],
        mode => sprintf('%04o', S_IMODE($stat[2])) };
}
sub verify_directory_identity {
    my ($snapshot, $label) = @_;
    my @stat = lstat($snapshot->{path});
    die "$label disappeared or changed type\n"
        unless @stat && S_ISDIR($stat[2]);
    die "$label identity or mode changed\n"
        unless $stat[0] == $snapshot->{device} && $stat[1] == $snapshot->{inode}
            && sprintf('%04o', S_IMODE($stat[2])) eq $snapshot->{mode};
}
sub verify_output_root {
    my ($published) = @_;
    verify_output_root_contents($published ? ('package-evidence.json') : ());
}
sub verify_output_root_contents {
    my (@expected) = @_;
    my @stat = lstat($output);
    die "Sealed output root disappeared or changed type\n"
        unless @stat && S_ISDIR($stat[2]);
    die "Sealed output root identity or mode changed\n"
        unless $stat[0] == $output_snapshot->{device}
            && $stat[1] == $output_snapshot->{inode}
            && sprintf('%04o', S_IMODE($stat[2])) eq $output_snapshot->{mode};
    my @actual = directory_entries($output);
    die "Sealed output root changed during production\n"
        unless canonical(\@actual) eq canonical(\@expected);
}
sub load_json {
    my ($path, $label) = @_;
    my $bytes = read_bounded($path, $limit{json_bytes}, $label);
    reject_duplicate_json_keys($bytes, $label);
    my $doc = eval { JSON::PP->new->utf8->decode($bytes) };
    die "Malformed $label JSON: $@\n" unless ref($doc) eq 'HASH';
    return $doc;
}
sub reject_duplicate_json_keys {
    my ($bytes, $label) = @_;
    my $position = 0;
    eval {
        json_scan_value($bytes, \$position, $label);
        json_scan_space($bytes, \$position);
        die "trailing data" unless $position == length($bytes);
        1;
    } or die "Malformed $label JSON or duplicate object key: $@\n";
}
sub json_scan_space {
    my ($bytes, $position) = @_;
    pos($bytes) = $$position;
    $bytes =~ /\G[\x20\x09\x0a\x0d]*/gc;
    $$position = pos($bytes);
}
sub json_scan_string {
    my ($bytes, $position) = @_;
    my $start = $$position;
    die "invalid JSON string" unless substr($bytes, $$position, 1) eq '"';
    $$position++;
    while ($$position < length($bytes)) {
        my $character = substr($bytes, $$position, 1);
        if ($character eq '"') {
            $$position++;
            return JSON::PP->new->decode(
                substr($bytes, $start, $$position - $start));
        }
        die "invalid JSON string control character"
            if ord($character) < 0x20;
        if ($character eq '\\') {
            $$position++;
            die "invalid JSON string escape" if $$position >= length($bytes);
            my $escape = substr($bytes, $$position, 1);
            if ($escape eq 'u') {
                my $hex = substr($bytes, $$position + 1, 4);
                die "invalid JSON Unicode escape"
                    unless length($hex) == 4 && $hex =~ /\A[0-9A-Fa-f]{4}\z/;
                $$position += 5;
                next;
            }
            die "invalid JSON string escape"
                unless $escape =~ /\A["\\\/bfnrt]\z/;
        }
        $$position++;
    }
    die "unterminated JSON string";
}
sub json_scan_value {
    my ($bytes, $position, $label) = @_;
    json_scan_space($bytes, $position);
    die "unexpected end of JSON" if $$position >= length($bytes);
    my $token = substr($bytes, $$position, 1);
    if ($token eq '{') {
        $$position++;
        my %seen;
        json_scan_space($bytes, $position);
        if (substr($bytes, $$position, 1) eq '}') { $$position++; return }
        while (1) {
            json_scan_space($bytes, $position);
            my $key = json_scan_string($bytes, $position);
            die "duplicate object key '$key' in $label" if $seen{$key}++;
            json_scan_space($bytes, $position);
            die "missing object colon" unless substr($bytes, $$position, 1) eq ':';
            $$position++;
            json_scan_value($bytes, $position, $label);
            json_scan_space($bytes, $position);
            my $separator = substr($bytes, $$position, 1);
            if ($separator eq '}') { $$position++; return }
            die "missing object separator" unless $separator eq ',';
            $$position++;
        }
    }
    if ($token eq '[') {
        $$position++;
        json_scan_space($bytes, $position);
        if (substr($bytes, $$position, 1) eq ']') { $$position++; return }
        while (1) {
            json_scan_value($bytes, $position, $label);
            json_scan_space($bytes, $position);
            my $separator = substr($bytes, $$position, 1);
            if ($separator eq ']') { $$position++; return }
            die "missing array separator" unless $separator eq ',';
            $$position++;
        }
    }
    if ($token eq '"') { json_scan_string($bytes, $position); return }
    pos($bytes) = $$position;
    die "invalid JSON value"
        unless $bytes =~ /\G(?:-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null)/gc;
    $$position = pos($bytes);
}
sub assert_exact_keys {
    my ($object, $label, @expected) = @_;
    die "$label must be an object\n" unless ref($object) eq 'HASH';
    my @actual = sort keys %$object;
    die "$label fields differ from the locked schema: " . join(', ', @actual) . "\n"
        unless canonical(\@actual) eq canonical([sort @expected]);
}
sub assert_allowed_keys {
    my ($object, $label, @allowed) = @_;
    die "$label must be an object\n" unless ref($object) eq 'HASH';
    my %allowed = map { $_ => 1 } @allowed;
    my @unexpected = sort grep { !$allowed{$_} } keys %$object;
    die "$label has unexpected fields: " . join(', ', @unexpected) . "\n"
        if @unexpected;
}
sub sha256_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $sha = Digest::SHA->new(256); $sha->addfile($fh);
    close $fh or die "Cannot close $path: $!\n";
    return $sha->hexdigest;
}
sub read_bounded {
    my ($path, $limit, $label) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my ($bytes, $chunk) = ('', '');
    while (1) {
        my $count = read($fh, $chunk, 65_536);
        die "Cannot read $path: $!\n" unless defined $count;
        last unless $count;
        die "$label exceeds byte limit\n" if length($bytes) + $count > $limit;
        $bytes .= $chunk;
    }
    close $fh or die "Cannot close $path: $!\n";
    return $bytes;
}
sub canonical { return JSON::PP->new->utf8->canonical->encode($_[0]) }
sub canonical_pretty { return JSON::PP->new->utf8->canonical->pretty->encode($_[0]) }
sub timestamp { return strftime('%Y-%m-%dT%H:%M:%SZ', gmtime($_[0])) }
sub bounded_decimal {
    my ($value, $maximum, $label) = @_;
    die "--$label must be a canonical positive decimal string within the production bound\n"
        unless defined($value) && $value =~ /\A[1-9][0-9]*\z/
            && (length($value) < length("$maximum")
                || (length($value) == length("$maximum") && $value le "$maximum"));
    return 0 + $value;
}
sub publish_evidence_bundle {
    my ($report, $sources) = @_;
    my $parent = dirname($output);
    my $stage = tempdir('.phase36-package-stage-XXXXXX', DIR => $parent,
        CLEANUP => 1);
    chmod 0700, $stage or die "Cannot protect package evidence staging directory: $!\n";
    my $stage_logs = File::Spec->catdir($stage, 'logs');
    mkdir $stage_logs, 0700 or die "Cannot create staged evidence log directory: $!\n";
    my (%stage_files, %descriptors);
    my %names = (
        jar => 'perlonjava-5.44.0.jar', java_bom => 'bom.json',
        perl_bom => 'perl-bom.json', sbom => 'sbom.json',
        deb => basename($sources->{deb}), notice_license => 'notice-license.json',
    );
    eval {
        for my $name (sort keys %names) {
            my $stage_path = File::Spec->catfile($stage, $names{$name});
            my $record = copy_snapshot($sources->{$name}, $stage_path,
                "retained $name artifact");
            $stage_files{$stage_path} = {
                final => File::Spec->catfile($output, 'package', $names{$name}),
                record => $record,
            };
            $descriptors{$name} = artifact_descriptor(
                File::Spec->catfile('package', $names{$name}), $record);
        }
        my %logs;
        for my $index (sort { $a <=> $b } keys %command_log_path) {
            my $name = $commands[$index]{name};
            $name =~ s/[^A-Za-z0-9_.-]+/-/g;
            my $filename = sprintf('%03d-%s.log', $index + 1, $name);
            my $stage_path = File::Spec->catfile($stage_logs, $filename);
            my $record = copy_log_snapshot($command_log_path{$index}, $stage_path,
                "retained command log $index");
            $stage_files{$stage_path} = {
                final => File::Spec->catfile($output, 'package', 'logs', $filename),
                record => $record,
            };
            $logs{sprintf('%03d-%s', $index + 1, $name)} = artifact_descriptor(
                File::Spec->catfile('package', 'logs', $filename), $record);
        }
        $report->{retained_artifacts} = {
            deliverables => { map { $_ => $descriptors{$_} }
                qw(jar sbom deb) },
            sbom_inputs => { map { $_ => $descriptors{$_} }
                qw(java_bom perl_bom) },
            logs => \%logs,
            notice_license => $descriptors{notice_license},
        };
        my $report_bytes = canonical_pretty($report);
        die "Package evidence report exceeds JSON byte limit\n"
            if length($report_bytes) > $limit{json_bytes};
        my $report_path = File::Spec->catfile($stage,
            'package-evidence-report.json');
        write_exclusive_synced($report_path, $report_bytes,
            'package evidence report');
        my $report_record = file_record($report_path, 'package evidence report');
        $stage_files{$report_path} = {
            final => File::Spec->catfile($output, 'package',
                'package-evidence-report.json'), record => $report_record,
        };
        my $final_root = File::Spec->catdir($output, 'package');
        mkdir $final_root, 0700
            or die "Cannot exclusively create retained package evidence directory: $!\n";
        $published_bundle_root = directory_identity($final_root,
            'retained package evidence directory');
        my $final_logs = File::Spec->catdir($final_root, 'logs');
        mkdir $final_logs, 0700
            or die "Cannot exclusively create retained log directory: $!\n";
        for my $stage_path (sort keys %stage_files) {
            my $entry = $stage_files{$stage_path};
            link($stage_path, $entry->{final})
                or die "Cannot exclusively publish nested evidence $entry->{final}: $!\n";
            my $final_record = { %{$entry->{record}}, path => $entry->{final} };
            verify_file_record($final_record, 'Published nested evidence');
            $published_bundle_records{$entry->{final}} = $final_record;
        }
        $bundle_published = 1;
        verify_output_root_contents('package');
        for my $path (sort keys %published_bundle_records) {
            verify_file_record($published_bundle_records{$path},
                'Published nested evidence');
        }
        my $retained = {
            report => artifact_descriptor(
                File::Spec->catfile('package', 'package-evidence-report.json'),
                $report_record),
            deliverables => { map { $_ => $descriptors{$_} }
                qw(jar sbom deb) },
            sbom_inputs => { map { $_ => $descriptors{$_} }
                qw(java_bom perl_bom) },
            logs => \%logs,
            notice_license => $descriptors{notice_license},
        };
        cleanup_stage($stage, [keys %stage_files]);
        return $retained;
    } or do {
        my $error = $@ || "Unknown nested evidence publication failure\n";
        cleanup_published_bundle();
        cleanup_stage($stage, [keys %stage_files]);
        die $error;
    };
}
sub copy_snapshot {
    my ($source_path, $destination, $label) = @_;
    my $canonical_source = abs_path($source_path)
        or die "Cannot resolve $label source\n";
    die "$label source must be a nonsymlink regular file\n"
        unless -f $canonical_source && !-l $source_path;
    $source_path = $canonical_source;
    my $source_record = file_record($source_path, $label);
    sysopen my $out, $destination, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot exclusively create $label copy: $!\n";
    binmode $out, ':raw';
    open my $in, '<:raw', $source_path or die "Cannot read $label: $!\n";
    my $copied = 0;
    while (1) {
        my $chunk = '';
        my $count = read($in, $chunk, 65_536);
        die "Cannot read $label: $!\n" unless defined $count;
        last unless $count;
        $copied += $count;
        die "$label copy exceeds artifact byte limit\n"
            if $copied > $limit{artifact_bytes};
        print {$out} $chunk or die "Cannot write $label copy: $!\n";
    }
    close $in or die "Cannot close $label source: $!\n";
    $out->flush or die "Cannot flush $label copy: $!\n";
    $out->sync or die "Cannot sync $label copy: $!\n";
    close $out or die "Cannot close $label copy: $!\n";
    verify_file_record($source_record, "$label source");
    my $copy_record = file_record($destination, "$label copy");
    die "$label retained copy differs from accepted source\n"
        unless $copy_record->{size} == $source_record->{size}
            && $copy_record->{sha256} eq $source_record->{sha256};
    return $copy_record;
}
sub copy_log_snapshot {
    my ($source_path, $destination, $label) = @_;
    my $canonical_source = abs_path($source_path)
        or die "Cannot resolve $label source\n";
    return copy_snapshot($canonical_source, $destination, $label)
        if -s $canonical_source;
    die "$label source must be an empty nonsymlink regular file\n"
        unless -f $canonical_source && !-l $source_path;
    my @before = stat($canonical_source);
    write_exclusive_synced($destination,
        "[command completed without output]\n", $label);
    my @after = stat($canonical_source);
    die "$label empty source identity changed while retaining it\n"
        unless @before && @after && $before[0] == $after[0]
            && $before[1] == $after[1] && $before[2] == $after[2]
            && $before[7] == 0 && $after[7] == 0;
    return file_record($destination, "$label retained representation");
}
sub write_exclusive_synced {
    my ($path, $bytes, $label) = @_;
    sysopen my $fh, $path, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot exclusively create $label: $!\n";
    binmode $fh, ':raw';
    print {$fh} $bytes or die "Cannot write $label: $!\n";
    $fh->flush or die "Cannot flush $label: $!\n";
    $fh->sync or die "Cannot sync $label: $!\n";
    close $fh or die "Cannot close $label: $!\n";
}
sub artifact_descriptor {
    my ($path, $record) = @_;
    $path =~ s{\\}{/}g;
    return { path => $path, sha256 => $record->{sha256}, size => $record->{size} };
}
sub cleanup_stage {
    my ($stage, $files) = @_;
    my $logs = File::Spec->catdir($stage, 'logs');
    if (-d $logs && !-l $logs) {
        unlink File::Spec->catfile($logs, $_) for directory_entries($logs);
        rmdir $logs;
    }
    if (-d $stage && !-l $stage) {
        unlink File::Spec->catfile($stage, $_)
            for grep { $_ ne 'logs' } directory_entries($stage);
    }
    rmdir $stage;
}
sub cleanup_published_bundle {
    for my $path (sort { length($b) <=> length($a) }
            keys %published_bundle_records) {
        unlink $path if same_file_identity($path, $published_bundle_records{$path});
        delete $published_bundle_records{$path} unless -e $path || -l $path;
    }
    if ($published_bundle_root) {
        rmdir File::Spec->catdir($published_bundle_root->{path}, 'logs');
        if (-d $published_bundle_root->{path}) {
            my @stat = lstat($published_bundle_root->{path});
            rmdir $published_bundle_root->{path}
                if @stat && $stat[0] == $published_bundle_root->{device}
                    && $stat[1] == $published_bundle_root->{inode};
        }
        $bundle_published = -e $published_bundle_root->{path} ? 1 : 0;
        $published_bundle_root = undef unless $bundle_published;
    } else {
        $bundle_published = 0;
    }
}
sub publish_atomic {
    my ($final, $bytes, $with_bundle) = @_;
    die "Evidence JSON exceeds byte limit\n" if length($bytes) > $limit{json_bytes};
    my $output_root = dirname($final);
    my $parent = dirname($output_root);
    my ($fh, $temporary) = tempfile('.package-evidence.XXXXXX', DIR => $parent, UNLINK => 0);
    binmode $fh, ':raw';
    unless (print {$fh} $bytes) {
        close $fh; unlink $temporary;
        die "Cannot write temporary evidence: $!\n";
    }
    $fh->flush or do { close $fh; unlink $temporary; die "Cannot flush temporary evidence: $!\n" };
    $fh->sync or do { close $fh; unlink $temporary; die "Cannot sync temporary evidence: $!\n" };
    unless (close $fh) { unlink $temporary; die "Cannot close temporary evidence: $!\n" }
    my $expected = file_record($temporary, 'publication staging evidence');
    verify_output_root_contents($with_bundle ? ('package') : ());
    verify_published_bundle() if $with_bundle;
    unless (link($temporary, $final)) {
        my $error = $!; unlink $temporary;
        die "Cannot exclusively atomically publish evidence: $error\n";
    }
    $linked_success_path = $final;
    $linked_success_record = $expected;
    $success_staging_path = $temporary;
    my $validated = eval {
        verify_published_link($final, $expected, $with_bundle);
        verify_final_source_identity($with_bundle);
        verify_published_link($final, $expected, $with_bundle);
        die "Injected success staging unlink failure\n"
            if test_fault('success-staging-unlink');
        unlink $temporary
            or die "Cannot remove publication staging link: $!\n";
        $success_staging_path = undef;
        verify_published_link($final, $expected, $with_bundle);
        1;
    };
    if (!$validated) {
        my $error = $@ || "unknown publication validation failure\n";
        my $cleanup_error = cleanup_linked_success();
        $error .= "Success publication cleanup failed: $cleanup_error\n"
            if length $cleanup_error;
        die $error;
    }
    ($linked_success_path, $linked_success_record, $success_staging_path)
        = (undef, undef, undef);
}
sub cleanup_linked_success {
    my @errors;
    if ($linked_success_path) {
        if (same_file_identity($linked_success_path, $linked_success_record)) {
            push @errors, "cannot remove owned success bridge: $!"
                unless unlink $linked_success_path;
        } elsif (-e $linked_success_path || -l $linked_success_path) {
            push @errors, 'success bridge identity changed before cleanup';
        }
    }
    if ($success_staging_path) {
        if (same_file_identity($success_staging_path, $linked_success_record)) {
            push @errors, "cannot remove owned success staging link: $!"
                unless unlink $success_staging_path;
        } elsif (-e $success_staging_path || -l $success_staging_path) {
            push @errors, 'success staging identity changed before cleanup';
        }
    }
    cleanup_published_bundle() if $bundle_published;
    eval { verify_output_root_contents() };
    push @errors, "sealed output root is not empty after cleanup: $@" if $@;
    $linked_success_path = undef
        unless defined($linked_success_path)
            && (-e $linked_success_path || -l $linked_success_path);
    $success_staging_path = undef
        unless defined($success_staging_path)
            && (-e $success_staging_path || -l $success_staging_path);
    $linked_success_record = undef
        unless $linked_success_path || $success_staging_path;
    return join('; ', @errors);
}
sub verify_published_link {
    my ($final, $expected, $with_bundle) = @_;
    verify_output_root_contents(
        $with_bundle ? ('package', 'package-evidence.json')
            : ('package-evidence.json'));
    die "Published evidence is a symlink or changed type\n" unless -f $final && !-l $final;
    my $actual = file_record($final, 'published evidence');
    die "Published evidence identity, mode, size, or hash changed\n"
        unless $actual->{device} == $expected->{device}
            && $actual->{inode} == $expected->{inode}
            && $actual->{mode} eq $expected->{mode}
            && $actual->{size} == $expected->{size}
            && $actual->{sha256} eq $expected->{sha256};
}
sub same_file_identity {
    my ($path, $expected) = @_;
    my @stat = lstat($path);
    return @stat && S_ISREG($stat[2])
        && $stat[0] == $expected->{device} && $stat[1] == $expected->{inode};
}
sub verify_final_source_identity {
    my ($with_bundle) = @_;
    verify_directory_identity($source_snapshot, 'source root');
    verify_tools();
    verify_protected();
    verify_generated_files();
    my $head_log = File::Spec->catfile($work, 'post-publication-git-head.log');
    my $head = run_child([$tool{git}{path}, '-C', $source, 'rev-parse', 'HEAD'],
        $head_log, $option{timeout}, \%base_env);
    assert_final_command($head, 'post-publication git HEAD');
    my $actual = read_bounded($head_log, $limit{log_bytes}, 'post-publication Git HEAD');
    $actual =~ s/\s+\z//;
    die "Post-publication source commit mismatch\n"
        unless $actual eq $option{expected_commit};
    my $status_log = File::Spec->catfile($work, 'post-publication-git-status.log');
    my $status = run_child([$tool{git}{path}, '-C', $source, 'status',
        '--porcelain=v1', '--untracked-files=all'], $status_log,
        $option{timeout}, \%base_env);
    assert_final_command($status, 'post-publication git status');
    die "Post-publication source tree is dirty or mutated\n"
        if length read_bounded($status_log, $limit{log_bytes},
            'post-publication Git status');
    verify_directory_identity($source_snapshot, 'source root');
    verify_tools();
    verify_protected();
    verify_generated_files();
    verify_published_bundle() if $with_bundle;
}
sub verify_published_bundle {
    die "Retained package evidence bundle is not published\n" unless $bundle_published;
    verify_directory_identity($published_bundle_root,
        'retained package evidence directory');
    for my $path (sort keys %published_bundle_records) {
        verify_file_record($published_bundle_records{$path},
            'Published nested evidence');
    }
}
sub assert_final_command {
    my ($result, $label) = @_;
    die "$label exceeded the command log byte limit\n" if $result->{log_overflow};
    die "$label timed out\n" if $result->{timeout};
    die "$label terminated by signal $result->{signal}\n" if $result->{signal};
    die "$label exited nonzero ($result->{exit_code})\n" if $result->{exit_code};
}
sub publish_failure_notice {
    my ($failure) = @_;
    return if $published_success || $option{mode} ne 'report';
    verify_output_root_contents();
    $failure = "$failure";
    $failure =~ s/[\x00-\x08\x0b\x0c\x0e-\x1f]/?/g;
    my $truncated = length($failure) > 4096 ? 1 : 0;
    $failure = substr($failure, 0, 4096);
    my $document = {
        schema_version => 1, kind => 'phase36-package-evidence-failure',
        producer => 'run_phase36_package_evidence.pl', mode => $option{mode},
        status => 'fail', verified => JSON::PP::false,
        identity => { source_commit => $option{expected_commit} },
        completion => {
            exit_code => 1, signal => 0, timeout => JSON::PP::false,
            incomplete => JSON::PP::true,
            review_stop => JSON::PP::false,
        },
        failure => { message => $failure,
            truncated => $truncated ? JSON::PP::true : JSON::PP::false },
    };
    my $bytes = canonical_pretty($document);
    die "Failure notice exceeds byte limit\n" if length($bytes) > MAX_FAILURE_BYTES;
    my $parent = dirname($output);
    my ($fh, $temporary) = tempfile('.package-evidence-failure.XXXXXX',
        DIR => $parent, UNLINK => 0);
    binmode $fh, ':raw';
    print {$fh} $bytes or do { close $fh; unlink $temporary; die "Cannot write failure notice: $!\n" };
    $fh->flush or do { close $fh; unlink $temporary; die "Cannot flush failure notice: $!\n" };
    $fh->sync or do { close $fh; unlink $temporary; die "Cannot sync failure notice: $!\n" };
    close $fh or do { unlink $temporary; die "Cannot close failure notice: $!\n" };
    my $expected = file_record($temporary, 'failure notice staging file');
    die "Failure notice staging file exceeds byte limit\n"
        if $expected->{size} > MAX_FAILURE_BYTES;
    my $final = File::Spec->catfile($output, 'package-evidence-failure.json');
    unless (link($temporary, $final)) {
        my $error = $!; unlink $temporary;
        die "Cannot exclusively publish failure notice: $error\n";
    }
    my $validated = eval {
        inject_test_mutation('report-final-mutation', $final);
        verify_file_record($expected, 'Failure notice staging source');
        verify_file_record({ %$expected, path => $final },
            'Published failure notice');
        verify_output_root_contents('package-evidence-failure.json');
        verify_file_record($expected, 'Failure notice staging source');
        verify_file_record({ %$expected, path => $final },
            'Published failure notice');
        unlink $temporary
            or die "Cannot remove failure notice staging link: $!\n";
        verify_file_record({ %$expected, path => $final },
            'Published failure notice');
        1;
    };
    if (!$validated) {
        my $error = $@ || "Unknown failure notice publication validation failure\n";
        unlink $final if same_file_identity($final, $expected);
        unlink $temporary if same_file_identity($temporary, $expected);
        eval { verify_output_root_contents() };
        $error .= "Failure notice cleanup failed: $@" if $@;
        die $error;
    }
}
sub test_fault {
    my ($name) = @_;
    return 0 unless $ENV{HARNESS_ACTIVE};
    return ($ENV{PERLONJAVA_PHASE36_TEST_FAULT} // '') eq $name;
}
sub inject_test_mutation {
    my ($name, $path) = @_;
    return unless test_fault($name);
    open my $fh, '>>:raw', $path
        or die "Cannot inject $name fault: $!\n";
    print {$fh} "injected-publication-mutation\n"
        or die "Cannot write $name fault: $!\n";
    close $fh or die "Cannot close $name fault: $!\n";
}
sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: run_phase36_package_evidence.pl --source-root ABSOLUTE_CANONICAL_DIR
       --expected-commit FULL_SHA --output-root EMPTY_CANONICAL_DIR
       --make ABS_EXE --perl ABS_EXE --git ABS_EXE --dpkg-deb ABS_EXE
       --java ABS_EXE --jar ABS_EXE [--timeout SECONDS]
       [--mode assertion|report]
       [--max-log-bytes N --max-json-bytes N --max-artifact-bytes N
        --max-tree-bytes N --max-tree-entries N --max-tree-depth N]

Produces final-freeze Phase 36 package evidence. The source must be a clean
checkout at exactly EXPECTED_COMMIT and must have no preexisting installDist,
standalone-JAR, SBOM, or Debian outputs. Trusted make executes `make -C SOURCE
deb`; the accepted build graph makes buildDeb depend on fresh installDist and
its distribution verifier. Every subprocess uses argv execution without a
shell and a common wall timeout. Trusted Java and jar must be from one canonical
installation directory; a private symlink-only PATH binds Gradle's bare perl,
git, java, and jar names to the accepted canonical executables. Optional bounds
may only lower, never raise, the compiled production limits. The three accepted
distribution, strict Joni packaging/SBOM, and strict notice/license verifiers
are invoked from the selected source root. Trusted dpkg-deb validates, lists,
and privately extracts the package for byte binding and path checks.

On success, bounded deliverable copies, input BOMs, command logs, notice output,
and a rich report are exclusively retained below OUTPUT_ROOT/package. The exact
packaging acceptance bridge package-evidence.json is published last and points
to those nested files only through bounded hash/size descriptors. Assertion mode
publishes nothing on failure. Report mode may publish only the bounded, distinct
package-evidence-failure.json record and still exits nonzero.
USAGE
    exit $status;
}
