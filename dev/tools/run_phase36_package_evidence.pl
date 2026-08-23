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
use JSON::PP;
use MIME::Base64 qw(encode_base64);
use POSIX qw(setpgid strftime WIFEXITED WEXITSTATUS WIFSIGNALED WTERMSIG);
use Time::HiRes qw(time sleep);

use constant MAX_LOG_BYTES => 1_048_576;
use constant MAX_JSON_BYTES => 8_388_608;
use constant MAX_ARTIFACT_BYTES => 536_870_912;
use constant MAX_TREE_BYTES => 1_073_741_824;
use constant MAX_TREE_ENTRIES => 100_000;
use constant MAX_TREE_DEPTH => 64;

my %limit = (
    log_bytes => MAX_LOG_BYTES, json_bytes => MAX_JSON_BYTES,
    artifact_bytes => MAX_ARTIFACT_BYTES, tree_bytes => MAX_TREE_BYTES,
    tree_entries => MAX_TREE_ENTRIES, tree_depth => MAX_TREE_DEPTH,
);

my %option = (timeout => 1800);
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
    'timeout=i' => \$option{timeout},
    'max-log-bytes=i' => \$option{max_log_bytes},
    'max-json-bytes=i' => \$option{max_json_bytes},
    'max-artifact-bytes=i' => \$option{max_artifact_bytes},
    'max-tree-bytes=i' => \$option{max_tree_bytes},
    'max-tree-entries=i' => \$option{max_tree_entries},
    'max-tree-depth=i' => \$option{max_tree_depth},
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
die "--timeout must be positive\n" unless $option{timeout} > 0;
for my $name (sort keys %limit) {
    my $option_name = 'max_' . $name;
    next unless defined $option{$option_name};
    die "--" . ($option_name =~ s/_/-/gr) . " must be positive and cannot raise the production bound\n"
        unless $option{$option_name} > 0 && $option{$option_name} <= $limit{$name};
    $limit{$name} = $option{$option_name};
}

my $source = canonical_directory($option{source_root}, 'source root', 1);
my $output = canonical_directory($option{output_root}, 'output root', 1);
die "Source root and output root must be disjoint\n"
    if contains_path($source, $output) || contains_path($output, $source);
my @occupied = directory_entries($output);
die "Sealed output root is not empty: $output\n" if @occupied;
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
my %generated_files;
my %generated_trees;
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

my $output_snapshot = directory_identity($output, 'sealed output root');
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
my $sbom = safe_existing_file($source, 'build', 'reports', 'sbom.json');
my $distributions = safe_existing_directory($source, 'build', 'distributions');
my $deb_name = join('_', $package_contract->{package}, $package_contract->{version},
    $package_contract->{architecture}) . '.deb';
assert_directory_names($distributions, [$deb_name], 'package distributions');
my $deb = safe_existing_file($distributions, $deb_name);
%generated_files = map { $_ => file_record($_, 'generated package artifact') }
    ($jar, $sbom, $deb);

my $install_tree = tree_record($install, {}, 'installDist');
$generated_trees{$install} = $install_tree;
my @install_jars = grep { $_->{type} eq 'file' && $_->{path} =~ m{\Alib/[^/]+\.jar\z} }
    @{$install_tree->{entries}};
die "installDist must contain exactly one runtime JAR\n" unless @install_jars == 1;
my $installed_jar = safe_existing_file($install, split m{/}, $install_jars[0]{path});
assert_same_file($jar, $installed_jar, 'standalone and installDist JAR');
assert_sbom_commit($sbom, $option{expected_commit});
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
    [$jar, 'standalone JAR'], [$sbom, 'merged SBOM'], [$deb, 'Debian package'],
);
my $document = {
    schema_version => 1,
    kind => 'phase36-package-evidence',
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
publish_atomic(File::Spec->catfile($output, 'package-evidence.json'), canonical_pretty($document));
print File::Spec->catfile($output, 'package-evidence.json'), "\n";

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
        log_encoding => 'base64', log_base64 => encode_base64($bytes, ''),
        started_at => $result->{started_at}, ended_at => $result->{ended_at},
        duration_seconds => $result->{duration_seconds},
        exit_code => $result->{exit_code}, signal => $result->{signal},
        timeout => $result->{timeout} ? JSON::PP::true : JSON::PP::false,
    };
    push @commands, $record;
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
}

sub assert_sbom_commit {
    my ($path, $expected) = @_;
    my $doc = load_json($path, 'merged SBOM');
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
    my @stat = lstat($output);
    die "Sealed output root disappeared or changed type\n"
        unless @stat && S_ISDIR($stat[2]);
    die "Sealed output root identity or mode changed\n"
        unless $stat[0] == $output_snapshot->{device}
            && $stat[1] == $output_snapshot->{inode}
            && sprintf('%04o', S_IMODE($stat[2])) eq $output_snapshot->{mode};
    my @expected = $published ? ('package-evidence.json') : ();
    my @actual = directory_entries($output);
    die "Sealed output root changed during production\n"
        unless canonical(\@actual) eq canonical(\@expected);
}
sub load_json {
    my ($path, $label) = @_;
    my $doc = eval { JSON::PP->new->utf8->decode(
        read_bounded($path, $limit{json_bytes}, $label)) };
    die "Malformed $label JSON: $@\n" unless ref($doc) eq 'HASH';
    return $doc;
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
sub publish_atomic {
    my ($final, $bytes) = @_;
    die "Evidence JSON exceeds byte limit\n" if length($bytes) > $limit{json_bytes};
    my $output_root = dirname($final);
    my $parent = dirname($output_root);
    my ($fh, $temporary) = tempfile('.package-evidence.XXXXXX', DIR => $parent, UNLINK => 0);
    binmode $fh, ':raw';
    unless (print {$fh} $bytes) {
        close $fh; unlink $temporary;
        die "Cannot write temporary evidence: $!\n";
    }
    unless (close $fh) { unlink $temporary; die "Cannot close temporary evidence: $!\n" }
    unless (link($temporary, $final)) {
        my $error = $!; unlink $temporary;
        die "Cannot exclusively atomically publish evidence: $error\n";
    }
    unlink $temporary or die "Cannot remove publication staging link: $!\n";
    verify_output_root(1);
}
sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: run_phase36_package_evidence.pl --source-root ABSOLUTE_CANONICAL_DIR
       --expected-commit FULL_SHA --output-root EMPTY_CANONICAL_DIR
       --make ABS_EXE --perl ABS_EXE --git ABS_EXE --dpkg-deb ABS_EXE
       --java ABS_EXE --jar ABS_EXE [--timeout SECONDS]
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

Transient logs stay outside OUTPUT_ROOT. On success exactly one structured
artifact, package-evidence.json, is atomically renamed into OUTPUT_ROOT; any
failure leaves the initially empty output root empty.
USAGE
    exit $status;
}
