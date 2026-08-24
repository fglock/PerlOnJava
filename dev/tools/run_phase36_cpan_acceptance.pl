#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path getcwd);
use Digest::SHA;
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Spec;
use FindBin;
use Getopt::Long qw(GetOptions);
use JSON::PP;
use POSIX qw(setpgid strftime);
use Time::HiRes qw(time);
use lib "$FindBin::Bin/lib";
use PerlOnJava::Phase36CpanJarSbom qw(decode_strict_json inspect_jar_sbom);

my %option = (
    policy => 'dev/tools/phase36_cpan_targets.json',
    version_timeout => 30,
);
my ($help, $jcpan_injected, $jperl_injected, $policy_injected);
validate_cli_tokens(\@ARGV);
GetOptions(
    'manifest=s' => \$option{manifest},
    'authority-marker=s' => \$option{authority_marker},
    'policy=s' => sub { $option{policy} = $_[1]; $policy_injected = 1 },
    'evidence-dir=s' => \$option{evidence_dir},
    'jcpan=s' => sub { $option{jcpan} = $_[1]; $jcpan_injected = 1 },
    'jperl=s' => sub { $option{jperl} = $_[1]; $jperl_injected = 1 },
    'version-timeout=i' => \$option{version_timeout},
    'prepare-only!' => \$option{prepare_only},
    'resume!' => \$option{resume},
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;
die "--evidence-dir is required\n"
    unless defined($option{evidence_dir}) && length($option{evidence_dir});
die "--version-timeout must be positive\n" unless $option{version_timeout} > 0;
die "--prepare-only requires explicitly injected --jcpan and --jperl\n"
    if $option{prepare_only} && (!$jcpan_injected || !$jperl_injected);
die "--prepare-only requires --manifest and rejects --authority-marker\n"
    if $option{prepare_only} && (!length($option{manifest} // '')
        || length($option{authority_marker} // ''));
die "Acceptance requires --authority-marker as its sole manifest authority\n"
    if !$option{prepare_only} && (!length($option{authority_marker} // '')
        || length($option{manifest} // ''));
die "Acceptance launchers and policy must come only from the authority bundle\n"
    if !$option{prepare_only} && ($jcpan_injected || $jperl_injected
        || $policy_injected);

my $authority = $option{prepare_only} ? undef
    : load_authority_bundle($option{authority_marker});
if ($authority) {
    $option{manifest} = $authority->{launch_record}{path};
    $option{policy} = $authority->{bridge}{inputs}{cpan_policy}{path};
}
my $manifest = $authority ? $authority->{launch}
    : load_json($option{manifest}, 'prepare-only manifest');
my $policy = $authority
    ? strict_document($authority->{protected}{authority_input_cpan_policy},
        'authority target policy')
    : load_json($option{policy}, 'target policy');
validate_policy($policy);
my $legacy_identity = validate_manifest($manifest,
    $option{prepare_only} ? 'prepare-only' : 'acceptance');
my $identity = $authority ? {
    %{$authority->{bridge}{identity}},
    execution_authorized => $authority->{bridge}{execution_authorized},
    authority_tuple_sha256 => $authority->{bridge}{tuple_sha256},
    authority_marker_sha256 => $authority->{marker_record}{sha256},
    authority_bridge_sha256 => $authority->{bridge_record}{sha256},
    authority_launch_sha256 => $authority->{launch_record}{sha256},
    authority_seal_sha256 => $authority->{seal_record}{sha256},
} : $legacy_identity;
my $inputs = $manifest->{inputs};
$option{jperl} //= $inputs->{jperl}{path};
$option{jcpan} //= $inputs->{jcpan}{path};
for my $pair ([$option{jperl}, 'jperl'], [$option{jcpan}, 'jcpan']) {
    die "$pair->[1] launcher is missing or not executable: $pair->[0]\n"
        unless -f $pair->[0] && -x $pair->[0];
}
die "injected jperl differs from manifest path\n"
    unless same_path($option{jperl}, $inputs->{jperl}{path});
die "injected jcpan differs from manifest path\n"
    unless same_path($option{jcpan}, $inputs->{jcpan}{path});

my $evidence = File::Spec->rel2abs($option{evidence_dir});
die "unsafe evidence directory: $evidence\n"
    if $evidence eq File::Spec->rootdir || $evidence eq ($ENV{HOME} // '');
make_path($evidence) unless -d $evidence;
die "evidence path is not a directory: $evidence\n" unless -d $evidence;
my %protected = protected_inputs($option{manifest}, $option{policy}, $inputs);
@protected{keys %{$authority->{protected}}} = values %{$authority->{protected}}
    if $authority;
verify_protected(\%protected);
my $trusted_git = $authority ? $authority->{bridge}{inputs}{git}{path} : 'git';
verify_checkout($inputs->{source}, $identity->{source_commit}, 'source', $trusted_git);
verify_checkout($inputs->{perl5}, $identity->{perl5_commit}, 'perl5', $trusted_git);
my $output = File::Spec->catfile($evidence, 'cpan-acceptance.json');
my @existing = directory_entries($evidence);
if (@existing) {
    die "Refusing nonempty evidence directory without --resume: $evidence\n"
        unless $option{resume};
    resume_existing($output, $policy, $identity, $inputs, $evidence, \%protected);
}
die "--resume requires retained evidence\n" if $option{resume} && !@existing;
reverify_authority_for_execution($authority) if $authority;

my $version_log = File::Spec->catfile($evidence, 'jperl-version.log');
my $version_run = run_child(
    argv => [$option{jperl}, '-v'], log => $version_log,
    timeout => $option{version_timeout}, environment => {
        JPERL_UNIMPLEMENTED => undef,
        PERLONJAVA_JAR => $inputs->{jar}{path},
    });
die "jperl identity probe failed\n" unless !$version_run->{timeout}
    && !$version_run->{signal} && $version_run->{exit_code} == 0;
my $version_text = read_raw($version_log);
my @reported = $version_text =~ /\b([0-9a-f]{7,40})\b/ig;
die "jperl -v does not report runner/source commit\n"
    unless grep { index($identity->{runner_commit}, lc $_) == 0 } @reported;
verify_protected(\%protected);

my @targets = @{$policy->{expected_targets}};
my %policy_by_name = map { $_->{name} => $_ } @{$policy->{targets}};
my %results;
my @artifacts = ({
    path => relative_path($version_log, $evidence),
    sha256 => sha256_file($version_log), kind => 'jperl-version',
});
my $total_tests = 0;
for my $target (@targets) {
    my $target_policy = $policy_by_name{$target};
    my %modes;
    my $target_total = 0;
    for my $mode (@{$target_policy->{required_modes}}) {
        verify_protected(\%protected);
        verify_checkout($inputs->{source}, $identity->{source_commit}, 'source', $trusted_git);
        verify_checkout($inputs->{perl5}, $identity->{perl5_commit}, 'perl5', $trusted_git);
        my $slug = slug("$target-$mode");
        my $mode_dir = File::Spec->catdir($evidence, 'runs', $slug);
        my $home = File::Spec->catdir($mode_dir, 'home');
        my $tmp = File::Spec->catdir($mode_dir, 'tmp');
        make_path($home, $tmp);
        my $log = File::Spec->catfile($mode_dir, 'raw.log');
        my %environment = (
            PERLONJAVA_JAR => $inputs->{jar}{path},
            PERLONJAVA_HOME => $home,
            HOME => $home,
            TMPDIR => $tmp,
            PERL_MM_USE_DEFAULT => 1,
            JPERL_INTERPRETER => $mode eq 'interpreter' ? 1 : undef,
            JPERL_UNIMPLEMENTED => undef,
            PHASE36_CPAN_TARGET => $target,
            PHASE36_CPAN_MODE => $mode,
        );
        my @argv = ($option{jcpan}, '-t', $target);
        my $run = run_child(argv => \@argv, log => $log,
            timeout => $target_policy->{timeout_seconds},
            environment => \%environment);
        my $analysis = analyze_log($log, $target_policy);
        my $execution_error = $run->{exit_code} == 255
            && read_raw($log) =~ /Cannot execute/ ? 1 : 0;
        my $passed = !$run->{timeout} && !$run->{signal}
            && $run->{exit_code} == 0 && !$execution_error
            && !$analysis->{zero_tap} && !$analysis->{malformed}
            && !$analysis->{truncated} && !$analysis->{failures}
            && !@{$analysis->{unapproved_warnings}};
        my $meta = {
            target => $target, mode => $mode,
            status => $passed ? 'pass' : 'fail',
            argv => \@argv,
            environment => { map { $_ => $environment{$_} }
                qw(PERLONJAVA_JAR PERLONJAVA_HOME HOME TMPDIR PERL_MM_USE_DEFAULT
                    JPERL_INTERPRETER JPERL_UNIMPLEMENTED
                    PHASE36_CPAN_TARGET PHASE36_CPAN_MODE) },
            environment_sha256 => Digest::SHA::sha256_hex(canonical({ map {
                $_ => $environment{$_} } qw(PERLONJAVA_JAR PERLONJAVA_HOME HOME
                    TMPDIR PERL_MM_USE_DEFAULT JPERL_INTERPRETER
                    JPERL_UNIMPLEMENTED PHASE36_CPAN_TARGET
                    PHASE36_CPAN_MODE) })),
            started_at => $run->{started_at}, ended_at => $run->{ended_at},
            duration_seconds => $run->{duration_seconds},
            exit_code => $run->{exit_code}, signal => $run->{signal},
            timeout => boolean($run->{timeout}),
            execution_error => boolean($execution_error),
            total_tests => $analysis->{total_tests},
            failures => $analysis->{failures}, skips => $analysis->{skips},
            zero_tap => boolean($analysis->{zero_tap}),
            malformed => boolean($analysis->{malformed}),
            truncated => boolean($analysis->{truncated}),
            warning_diagnostics => $analysis->{warning_diagnostics},
            unapproved_warnings => $analysis->{unapproved_warnings},
            raw_log => { path => relative_path($log, $evidence),
                sha256 => sha256_file($log) },
            identity => { %$identity, jar_path => $inputs->{jar}{path},
                sbom_path => $inputs->{sbom}{path} },
        };
        my $meta_path = File::Spec->catfile($mode_dir, 'result.json');
        write_json($meta_path, $meta);
        push @artifacts,
            { path => relative_path($log, $evidence), sha256 => sha256_file($log), kind => 'raw-log' },
            { path => relative_path($meta_path, $evidence), sha256 => sha256_file($meta_path), kind => 'mode-result' };
        $modes{$mode} = $meta;
        $target_total += $analysis->{total_tests};
    }
    my $target_pass = !grep { $modes{$_}{status} ne 'pass' } keys %modes;
    $results{$target} = {
        status => $target_pass ? 'pass' : 'fail',
        total_tests => $target_total,
        timeout => boolean(grep { $modes{$_}{timeout} } keys %modes),
        truncated => boolean(grep { $modes{$_}{truncated} || $modes{$_}{malformed} } keys %modes),
        execution_error => boolean(grep { $modes{$_}{execution_error} } keys %modes),
        rationale => $target_policy->{rationale},
        focused_selector_permitted => boolean($target_policy->{focused_selector_permitted}),
        modes => \%modes,
    };
    $total_tests += $target_total;
}
verify_protected(\%protected);
verify_checkout($inputs->{source}, $identity->{source_commit}, 'source', $trusted_git);
verify_checkout($inputs->{perl5}, $identity->{perl5_commit}, 'perl5', $trusted_git);
my $all_pass = !grep { $results{$_}{status} ne 'pass' } @targets;
my $document = {
    schema_version => 2,
    mode => $option{prepare_only} ? 'prepare-only' : 'acceptance',
    status => $all_pass ? 'pass' : 'fail',
    expected_targets => \@targets,
    results => \%results,
    total_tests => $total_tests,
    excluded_audits => [],
    identity => { %$identity,
        manifest_sha256 => $protected{manifest}{sha256},
        policy_sha256 => $protected{policy}{sha256},
        jcpan_sha256 => $protected{jcpan}{sha256},
        inputs => $inputs,
    },
    artifacts => \@artifacts,
};
$document->{authority} = {
    schema => 'perlonjava.phase36.cpan-launch-authority/v1',
    execution_authorized => JSON::PP::true,
    tuple_sha256 => $authority->{bridge}{tuple_sha256},
    marker_sha256 => $authority->{marker_record}{sha256},
    bridge_sha256 => $authority->{bridge_record}{sha256},
    launch_sha256 => $authority->{launch_record}{sha256},
    seal_sha256 => $authority->{seal_record}{sha256},
} if $authority;
write_json($output, $document);
write_raw("$output.sha256", sha256_file($output) . "  cpan-acceptance.json\n");
print "Phase 36 CPAN acceptance: $output\n";
exit($all_pass ? 0 : 1);

sub load_authority_bundle {
    my ($marker_path) = @_;
    my %maximum = (json => 16 * 1024 * 1024, seal => 512,
        launcher => 16 * 1024 * 1024, jar => 2 * 1024 * 1024 * 1024,
        sbom => 256 * 1024 * 1024, baseline => 512 * 1024 * 1024,
        corpus => 4 * 1024 * 1024);
    my $marker_record = authority_file($marker_path, 'authority marker', $maximum{json});
    my $marker = strict_document($marker_record, 'authority marker');
    exact_keys($marker, 'authority marker', qw(schema_version kind authoritative
        execution_authorized tuple_sha256 launch_manifest bridge seal));
    die "Authority marker is not authoritative schema version 1\n"
        unless ($marker->{schema_version} // 0) == 1
            && ($marker->{kind} // '') eq 'phase36-cpan-launch-authority'
            && JSON::PP::is_bool($marker->{authoritative}) && $marker->{authoritative}
            && JSON::PP::is_bool($marker->{execution_authorized})
            && ($marker->{tuple_sha256} // '') =~ /\A[0-9a-f]{64}\z/;
    my %bundle_record;
    for my $name (qw(launch_manifest bridge seal)) {
        my $descriptor = $marker->{$name};
        exact_keys($descriptor, "authority $name descriptor", qw(path sha256 size));
        $bundle_record{$name} = authority_file($descriptor->{path},
            "authority $name", $name eq 'seal' ? $maximum{seal} : $maximum{json});
        verify_authority_descriptor($descriptor, $bundle_record{$name},
            "authority $name");
    }
    die "Authority bundle member paths are not canonical siblings\n"
        unless $marker_record->{path} eq $bundle_record{launch_manifest}{path}
                . '.authority.json'
            && $bundle_record{bridge}{path} eq $bundle_record{launch_manifest}{path}
                . '.bridge.json'
            && $bundle_record{seal}{path} eq $bundle_record{launch_manifest}{path}
                . '.bridge.sha256';
    my $bridge = strict_document($bundle_record{bridge}, 'authority bridge');
    exact_keys($bridge, 'authority bridge', qw(schema_version kind authoritative
        execution_authorized authority_marker_required tuple_sha256 launch_manifest
        identity inputs evidence));
    die "Authority bridge is not authoritative schema version 1\n"
        unless ($bridge->{schema_version} // 0) == 1
            && ($bridge->{kind} // '') eq 'phase36-cpan-launch-bridge'
            && JSON::PP::is_bool($bridge->{authoritative}) && $bridge->{authoritative}
            && JSON::PP::is_bool($bridge->{execution_authorized})
            && JSON::PP::is_bool($bridge->{authority_marker_required})
            && $bridge->{authority_marker_required};
    die "Execution authorization differs between marker and bridge\n"
        unless ($marker->{execution_authorized} ? 1 : 0)
            == ($bridge->{execution_authorized} ? 1 : 0);
    die "Authority tuple differs between marker and bridge\n"
        unless ($bridge->{tuple_sha256} // '') eq $marker->{tuple_sha256};
    my $tuple = Digest::SHA::sha256_hex(canonical({
        execution_authorized => $bridge->{execution_authorized},
        identity => $bridge->{identity}, inputs => $bridge->{inputs},
        evidence => $bridge->{evidence} }));
    die "Authority bridge tuple seal is invalid\n"
        unless $tuple eq $bridge->{tuple_sha256};
    exact_keys($bridge->{launch_manifest}, 'bridge launch descriptor',
        qw(path sha256 size schema));
    die "Bridge launch schema is not legacy-cpan-launch/v1\n"
        unless ($bridge->{launch_manifest}{schema} // '') eq 'legacy-cpan-launch/v1';
    verify_authority_descriptor({ map { $_ => $bridge->{launch_manifest}{$_} }
        qw(path sha256 size) }, $bundle_record{launch_manifest}, 'bridge launch manifest');

    if (!$bridge->{execution_authorized}) {
        my $seal_text = read_record($bundle_record{seal}, 'authority bridge seal');
        die "Authority bridge seal is malformed\n"
            unless $seal_text eq $bundle_record{bridge}{sha256} . '  '
                . File::Basename::basename($bundle_record{bridge}{path}) . "\n";
        my $launch = strict_document($bundle_record{launch_manifest},
            'non-executable authority launch manifest');
        my $legacy_identity = validate_manifest($launch, 'prepare-only');
        die "Non-executable authority launch mode is not prepare-only\n"
            unless ($launch->{mode} // '') eq 'prepare-only';
        for my $name (qw(source_commit runner_commit perl5_commit jperl_sha256
                jar_sha256 sbom_sha256)) {
            die "Non-executable authority launch identity differs from bridge: $name\n"
                unless ($legacy_identity->{$name} // '')
                    eq (($bridge->{identity}{$name}) // '');
        }
        die "Authority bundle is integrity-authoritative but not authorized for CPAN execution\n";
    }

    my @identity_keys = qw(source_commit runner_commit perl5_commit
        jar_embedded_commit jperl_sha256 jcpan_sha256 git_sha256 jar_sha256
        sbom_sha256 baseline_sha256 corpus_manifest_sha256 requirements_sha256
        cpan_policy_sha256 package_evidence_sha256 make_evidence_sha256
        package_producer_sha256 make_producer_sha256 make_seal_sha256
        actual_jar_embedded_commit embedded_sbom_sha256 sbom_relation_sha256);
    exact_keys($bridge->{identity}, 'bridge identity', @identity_keys);
    for my $name (@identity_keys) {
        next if $name =~ /commit\z/;
        die "Bridge identity $name is not SHA-256\n"
            unless ($bridge->{identity}{$name} // '') =~ /\A[0-9a-f]{64}\z/;
    }
    for my $name (qw(source_commit runner_commit perl5_commit
            jar_embedded_commit actual_jar_embedded_commit)) {
        die "Bridge identity $name is not a full Git SHA\n"
            unless ($bridge->{identity}{$name} // '') =~ /\A[0-9a-f]{40}\z/;
    }
    die "Bridge source/runner/JAR commit tuple is inconsistent\n"
        unless $bridge->{identity}{source_commit} eq $bridge->{identity}{runner_commit}
            && $bridge->{identity}{source_commit} eq $bridge->{identity}{jar_embedded_commit}
            && $bridge->{identity}{source_commit} eq
                $bridge->{identity}{actual_jar_embedded_commit};

    my @file_inputs = qw(jperl jcpan git jar sbom baseline corpus_manifest
        requirements cpan_policy package_evidence make_evidence package_producer
        make_producer make_seal);
    exact_keys($bridge->{inputs}, 'bridge inputs', qw(source perl5), @file_inputs);
    for my $checkout (qw(source perl5)) {
        exact_keys($bridge->{inputs}{$checkout}, "bridge $checkout checkout", qw(path commit));
        die "Bridge $checkout checkout path is not canonical absolute\n"
            unless canonical_directory_path($bridge->{inputs}{$checkout}{path});
    }
    my %protected = (
        authority_marker => $marker_record,
        authority_bridge => $bundle_record{bridge},
        authority_launch => $bundle_record{launch_manifest},
        authority_seal => $bundle_record{seal},
    );
    for my $name (@file_inputs) {
        my $limit = $name eq 'jar' ? $maximum{jar}
            : $name eq 'sbom' ? $maximum{sbom}
            : $name eq 'baseline' ? $maximum{baseline}
            : $name eq 'corpus_manifest' ? $maximum{corpus}
            : $name =~ /(?:jperl|jcpan|git|producer)/ ? $maximum{launcher}
            : $maximum{json};
        my $descriptor = $bridge->{inputs}{$name};
        exact_keys($descriptor, "bridge $name input", qw(path sha256 size));
        my $record = authority_file($descriptor->{path}, "bridge $name input", $limit);
        verify_authority_descriptor($descriptor, $record, "bridge $name input");
        $protected{"authority_input_$name"} = $record;
    }
    die "Authority Git is not executable\n" unless -x $bridge->{inputs}{git}{path};
    die "Authority launchers are not executable\n"
        unless -x $bridge->{inputs}{jperl}{path} && -x $bridge->{inputs}{jcpan}{path};

    my $identity = $bridge->{identity};
    my %hash_binding = (jperl => 'jperl_sha256', jcpan => 'jcpan_sha256',
        git => 'git_sha256', jar => 'jar_sha256', sbom => 'sbom_sha256',
        baseline => 'baseline_sha256', corpus_manifest => 'corpus_manifest_sha256',
        requirements => 'requirements_sha256', cpan_policy => 'cpan_policy_sha256',
        package_evidence => 'package_evidence_sha256',
        make_evidence => 'make_evidence_sha256',
        package_producer => 'package_producer_sha256',
        make_producer => 'make_producer_sha256', make_seal => 'make_seal_sha256');
    for my $name (sort keys %hash_binding) {
        die "Bridge identity hash differs from $name input\n"
            unless $identity->{$hash_binding{$name}} eq $bridge->{inputs}{$name}{sha256};
    }

    my $source = $bridge->{inputs}{source}{path};
    my $git = $bridge->{inputs}{git}{path};
    for my $name (qw(package make)) {
        my $relative = "dev/tools/run_phase36_${name}_evidence.pl";
        my $expected = File::Spec->catfile($source, File::Spec->splitdir($relative));
        die "Authority $name producer is not the canonical source producer\n"
            unless $bridge->{inputs}{"${name}_producer"}{path} eq $expected;
        capture_bounded([$git, '-C', $source, 'ls-files', '--error-unmatch', '--',
            $relative], 4096, "tracked $name producer");
    }

    my $seal_text = read_record($bundle_record{seal}, 'authority bridge seal');
    die "Authority bridge seal is malformed\n"
        unless $seal_text eq $bundle_record{bridge}{sha256} . '  '
            . File::Basename::basename($bundle_record{bridge}{path}) . "\n";
    my $launch = strict_document($bundle_record{launch_manifest}, 'authority launch manifest');
    my $legacy_identity = validate_manifest($launch, 'acceptance');
    for my $name (keys %$legacy_identity) {
        die "Authority launch identity differs from bridge: $name\n"
            unless $legacy_identity->{$name} eq $identity->{$name};
    }
    for my $name (qw(source perl5)) {
        my $bridge_input = $bridge->{inputs}{$name};
        die "Authority launch input differs from bridge: $name\n"
            unless canonical($launch->{inputs}{$name}) eq canonical($bridge_input);
    }
    for my $name (qw(jperl jcpan jar sbom)) {
        die "Authority launch input differs from bridge: $name\n"
            unless ($launch->{inputs}{$name}{path} // '') eq
                    $bridge->{inputs}{$name}{path}
                && ($launch->{inputs}{$name}{sha256} // '') eq
                    $bridge->{inputs}{$name}{sha256};
    }

    exact_keys($bridge->{evidence}, 'bridge evidence',
        qw(corpus package make actual_jar_sbom));
    exact_keys($bridge->{evidence}{corpus}, 'bridge corpus evidence', qw(path sha256));
    die "Bridge corpus evidence differs from authority input\n"
        unless $bridge->{evidence}{corpus}{path} eq $bridge->{inputs}{corpus_manifest}{path}
            && $bridge->{evidence}{corpus}{sha256} eq
                $bridge->{inputs}{corpus_manifest}{sha256};
    validate_package_authority($bridge, \%protected, \%maximum);
    validate_make_authority($bridge, \%protected, \%maximum);
    exact_keys($bridge->{evidence}{actual_jar_sbom}, 'actual JAR/SBOM evidence',
        qw(jar_embedded_commit embedded_sbom_sha256 sbom_relation_sha256));
    my $actual = inspect_jar_sbom($bridge->{inputs}{jar}{path},
        $bridge->{inputs}{sbom}{path}, $identity->{source_commit});
    die "Actual selected JAR/SBOM evidence differs from bridge\n"
        unless canonical($actual) eq canonical($bridge->{evidence}{actual_jar_sbom})
            && $actual->{jar_embedded_commit} eq $identity->{actual_jar_embedded_commit}
            && $actual->{embedded_sbom_sha256} eq $identity->{embedded_sbom_sha256}
            && $actual->{sbom_relation_sha256} eq $identity->{sbom_relation_sha256};

    verify_protected(\%protected);
    return { marker => $marker, bridge => $bridge, launch => $launch,
        marker_record => $marker_record, bridge_record => $bundle_record{bridge},
        launch_record => $bundle_record{launch_manifest},
        seal_record => $bundle_record{seal}, protected => \%protected };
}

sub reverify_authority_for_execution {
    my ($authority) = @_;
    verify_protected($authority->{protected});
    my $bridge = $authority->{bridge};
    my $actual = inspect_jar_sbom($bridge->{inputs}{jar}{path},
        $bridge->{inputs}{sbom}{path}, $bridge->{identity}{source_commit});
    die "Actual selected JAR/SBOM evidence changed before CPAN execution\n"
        unless canonical($actual) eq canonical($bridge->{evidence}{actual_jar_sbom});
    my $git = $bridge->{inputs}{git}{path};
    my $source = $bridge->{inputs}{source}{path};
    for my $name (qw(package make)) {
        my $relative = "dev/tools/run_phase36_${name}_evidence.pl";
        capture_bounded([$git, '-C', $source, 'ls-files', '--error-unmatch', '--',
            $relative], 4096, "tracked $name producer before CPAN execution");
    }
    verify_protected($authority->{protected});
}

sub validate_package_authority {
    my ($bridge, $protected, $maximum) = @_;
    my $input = $bridge->{inputs}{package_evidence};
    die "Package authority marker must be package-evidence.json\n"
        unless File::Basename::basename($input->{path}) eq 'package-evidence.json';
    my $package = strict_document($protected->{authority_input_package_evidence},
        'package authority marker');
    exact_keys($package, 'package authority marker', qw(schema_version kind producer
        verified identity completion artifacts missing_entries duplicate_entries));
    die "Package authority marker is not an accepted canonical producer record\n"
        unless ($package->{schema_version} // 0) == 1
            && ($package->{kind} // '') eq 'packaging'
            && ($package->{producer} // '') eq 'run_phase36_package_evidence.pl'
            && JSON::PP::is_bool($package->{verified}) && $package->{verified}
            && ($package->{missing_entries} // -1) == 0
            && ($package->{duplicate_entries} // -1) == 0;
    exact_keys($package->{identity}, 'package authority identity',
        qw(source_commit jar_sha256 sbom_sha256));
    for my $name (qw(source_commit jar_sha256 sbom_sha256)) {
        die "Package authority identity differs from bridge: $name\n"
            unless $package->{identity}{$name} eq $bridge->{identity}{$name};
    }
    exact_keys($bridge->{evidence}{package}, 'bridge package evidence',
        qw(path sha256 identity producer));
    die "Bridge package evidence path/hash/identity differs from marker\n"
        unless $bridge->{evidence}{package}{path} eq $input->{path}
            && $bridge->{evidence}{package}{sha256} eq $input->{sha256}
            && canonical($bridge->{evidence}{package}{identity})
                eq canonical($package->{identity});
    verify_authority_descriptor($bridge->{evidence}{package}{producer},
        $protected->{authority_input_package_producer}, 'bridge package producer');

    my $base = File::Basename::dirname($input->{path});
    my @descriptors = (['report', $package->{artifacts}{report}],
        (map { ["deliverable $_", $package->{artifacts}{deliverables}{$_}] }
            sort keys %{$package->{artifacts}{deliverables} // {}}),
        (map { ["SBOM input $_", $package->{artifacts}{sbom_inputs}{$_}] }
            sort keys %{$package->{artifacts}{sbom_inputs} // {}}),
        (map { ["log $_", $package->{artifacts}{logs}{$_}] }
            sort keys %{$package->{artifacts}{logs} // {}}),
        ['notice/license', $package->{artifacts}{notice_license}]);
    my %record;
    for my $pair (@descriptors) {
        my ($name, $descriptor) = @$pair;
        exact_keys($descriptor, "package $name descriptor", qw(path sha256 size));
        die "Package $name descriptor path is unsafe\n"
            if File::Spec->file_name_is_absolute($descriptor->{path} // '')
                || grep { $_ eq '..' || $_ eq '.' || !length($_) }
                    File::Spec->splitdir($descriptor->{path} // '');
        my $path = File::Spec->catfile($base, File::Spec->splitdir($descriptor->{path}));
        my $item = authority_file($path, "package $name artifact",
            $name =~ /deliverable|SBOM input/ ? 2 * 1024 * 1024 * 1024
                : 256 * 1024 * 1024);
        verify_authority_descriptor({ %$descriptor, path => $path }, $item,
            "package $name artifact");
        $protected->{"package_$name"} = $item;
        $record{$name} = $item;
    }
    die "Retained package JAR/SBOM differs from selected authority bytes\n"
        unless $record{'deliverable jar'}{sha256} eq $bridge->{identity}{jar_sha256}
            && $record{'deliverable sbom'}{sha256} eq $bridge->{identity}{sbom_sha256};
    my $report = strict_document($record{report}, 'retained package report');
    die "Retained package report producer is not canonical\n"
        unless ($report->{producer} // '') eq 'run_phase36_package_evidence.pl'
            && ($report->{identity}{source_commit} // '') eq
                $bridge->{identity}{source_commit}
            && ($report->{jar_sha256} // '') eq $bridge->{identity}{jar_sha256}
            && ($report->{sbom_sha256} // '') eq $bridge->{identity}{sbom_sha256}
            && ($report->{sbom_relation}{relation} // '') eq
                'java-components+joni-fork+perl-components'
            && JSON::PP::is_bool($report->{sbom_relation}{verified})
            && $report->{sbom_relation}{verified}
            && ($report->{sbom_relation}{java_bom_sha256} // '') eq
                $record{'SBOM input java_bom'}{sha256}
            && ($report->{sbom_relation}{perl_bom_sha256} // '') eq
                $record{'SBOM input perl_bom'}{sha256}
            && ($report->{sbom_relation}{sbom_sha256} // '') eq
                $record{'deliverable sbom'}{sha256};
}

sub validate_make_authority {
    my ($bridge, $protected, $maximum) = @_;
    my $make = strict_document($protected->{authority_input_make_evidence},
        'make authority evidence');
    die "Make authority evidence is not an accepted canonical producer record\n"
        unless ($make->{schema} // '') eq 'perlonjava.phase36.make-evidence/v1'
            && ($make->{kind} // '') eq 'make' && ($make->{mode} // '') eq 'acceptance'
            && ($make->{status} // '') eq 'pass'
            && ($make->{producer} // '') eq 'run_phase36_make_evidence.pl'
            && JSON::PP::is_bool($make->{authoritative}) && $make->{authoritative}
            && JSON::PP::is_bool($make->{verified}) && $make->{verified};
    for my $name (qw(source_commit runner_commit jar_sha256 jar_reported_commit
            jar_embedded_commit)) {
        my $expected = $name eq 'jar_reported_commit'
            ? $bridge->{identity}{source_commit} : $bridge->{identity}{$name};
        die "Make authority identity differs from bridge: $name\n"
            unless ($make->{identity}{$name} // '') eq $expected;
    }
    verify_authority_descriptor($make->{tools}{producer},
        $protected->{authority_input_make_producer}, 'make canonical producer');
    exact_keys($bridge->{evidence}{make}, 'bridge make evidence',
        qw(path sha256 identity producer seal));
    die "Bridge make evidence path/hash/identity differs from selected record\n"
        unless $bridge->{evidence}{make}{path} eq $bridge->{inputs}{make_evidence}{path}
            && $bridge->{evidence}{make}{sha256} eq $bridge->{inputs}{make_evidence}{sha256}
            && canonical($bridge->{evidence}{make}{identity}) eq canonical($make->{identity});
    verify_authority_descriptor($bridge->{evidence}{make}{producer},
        $protected->{authority_input_make_producer}, 'bridge make producer');
    verify_authority_descriptor($bridge->{evidence}{make}{seal},
        $protected->{authority_input_make_seal}, 'bridge make external seal');
    die "Make external seal is not the canonical evidence sibling\n"
        unless $bridge->{inputs}{make_seal}{path} eq
            $bridge->{inputs}{make_evidence}{path} . '.seal';
    my %make_record;
    for my $name (sort keys %{$make->{tools} // {}}) {
        my $descriptor = $make->{tools}{$name};
        my $plain = $name eq 'producer' ? $descriptor
            : { map { $_ => $descriptor->{$_} } qw(path sha256 size) };
        my $record = authority_file($plain->{path}, "make tool $name",
            256 * 1024 * 1024);
        verify_authority_descriptor($plain, $record, "make tool $name");
        $protected->{"make_tool_$name"} = $record;
    }
    for my $group (qw(inputs artifacts)) {
        for my $name (sort keys %{$make->{$group} // {}}) {
            my $descriptor = $make->{$group}{$name};
            my $record = authority_file($descriptor->{path},
                "make $group $name", 2 * 1024 * 1024 * 1024);
            verify_authority_descriptor($descriptor, $record,
                "make $group $name");
            $protected->{"make_${group}_$name"} = $record;
            $make_record{"${group}_$name"} = $record;
        }
    }
    die "Make retained JAR differs from selected authority JAR\n"
        unless $make_record{artifacts_jar}{path} eq $bridge->{inputs}{jar}{path}
            && $make_record{artifacts_jar}{sha256} eq $bridge->{identity}{jar_sha256};
    my $embedded = strict_document($make_record{artifacts_jar_embedded},
        'retained make embedded-JAR evidence');
    die "Retained make embedded-JAR claim differs from actual authority bytes\n"
        unless ($embedded->{resolved_commit} // '') eq
                $bridge->{identity}{actual_jar_embedded_commit}
            && ($embedded->{jar_sha256} // '') eq $bridge->{identity}{jar_sha256};
    die "Make trusted Git differs from authority Git\n"
        unless ($make->{tools}{git}{path} // '') eq $bridge->{inputs}{git}{path}
            && ($make->{tools}{git}{sha256} // '') eq $bridge->{identity}{git_sha256};
    my %payload = %$make; delete $payload{seal};
    die "Make authority payload seal is invalid\n"
        unless ($make->{seal}{algorithm} // '') eq 'SHA-256'
            && ($make->{seal}{payload_sha256} // '') eq
                Digest::SHA::sha256_hex(canonical(\%payload));
    my $seal = read_record($protected->{authority_input_make_seal},
        'make external seal');
    die "Make external authority seal is invalid\n"
        unless $seal eq 'SHA-256 ' . $make->{seal}{payload_sha256} . ' '
            . $bridge->{inputs}{make_evidence}{sha256} . "\n";
}

sub authority_file {
    my ($path, $label, $maximum) = @_;
    die "$label path must be canonical absolute\n"
        unless defined($path) && File::Spec->file_name_is_absolute($path)
            && defined(abs_path($path)) && abs_path($path) eq $path;
    my @before = lstat($path);
    die "$label must be a nonsymlink regular file\n" unless @before && -f _ && !-l _;
    die "$label is empty or exceeds its bounded byte limit\n"
        unless $before[7] > 0 && $before[7] <= $maximum;
    my $sha = sha256_file($path);
    my @after = lstat($path);
    die "$label changed while it was hashed\n"
        unless @after && $after[0] == $before[0] && $after[1] == $before[1]
            && $after[2] == $before[2] && $after[7] == $before[7]
            && $after[9] == $before[9];
    return { path => $path, sha256 => $sha, size => 0 + $before[7],
        dev => 0 + $before[0], inode => 0 + $before[1], mode => 0 + $before[2],
        mtime => 0 + $before[9] };
}

sub strict_document { decode_strict_json(read_record($_[0], $_[1]), $_[1]) }
sub read_record {
    my ($record, $label) = @_;
    open my $fh, '<:raw', $record->{path} or die "Cannot read $label: $!\n";
    local $/; my $bytes = <$fh>; close $fh or die "Cannot close $label: $!\n";
    die "$label changed while it was read\n"
        unless length($bytes) == $record->{size}
            && Digest::SHA::sha256_hex($bytes) eq $record->{sha256};
    return $bytes;
}
sub verify_authority_descriptor {
    my ($descriptor, $record, $label) = @_;
    die "$label descriptor differs from retained bytes\n"
        unless ($descriptor->{path} // '') eq $record->{path}
            && ($descriptor->{sha256} // '') eq $record->{sha256}
            && defined($descriptor->{size}) && !ref($descriptor->{size})
            && $descriptor->{size} =~ /\A[0-9]+\z/
            && $descriptor->{size} == $record->{size};
}
sub exact_keys {
    my ($value, $label, @keys) = @_;
    die "$label must be an object\n" unless ref($value) eq 'HASH';
    die "$label has missing or extra fields\n"
        unless canonical([sort keys %$value]) eq canonical([sort @keys]);
}
sub canonical_directory_path {
    my ($path) = @_;
    return defined($path) && File::Spec->file_name_is_absolute($path)
        && -d $path && !-l $path && defined(abs_path($path))
        && abs_path($path) eq $path;
}

sub validate_manifest {
    my ($manifest, $execution_mode) = @_;
    die "Acceptance manifest schema_version must be 1\n"
        unless ($manifest->{schema_version} // 0) == 1;
    my $valid_mode = $execution_mode eq 'prepare-only'
        ? (($manifest->{mode} // '') =~ /\A(?:acceptance|prepare-only)\z/)
        : (($manifest->{mode} // '') eq 'acceptance');
    die "Acceptance manifest mode must be acceptance\n" unless $valid_mode;
    my $identity = $manifest->{identity};
    die "Acceptance manifest identity is missing\n" unless ref $identity eq 'HASH';
    for my $field (qw(source_commit runner_commit perl5_commit)) {
        die "Acceptance manifest $field is not a full Git SHA\n"
            unless ($identity->{$field} // '') =~ /\A[0-9a-f]{40}\z/;
    }
    die "Acceptance manifest runner commit differs from source commit\n"
        unless $identity->{runner_commit} eq $identity->{source_commit};
    for my $field (qw(jperl_sha256 jar_sha256 sbom_sha256)) {
        die "Acceptance manifest $field is not SHA-256\n"
            unless ($identity->{$field} // '') =~ /\A[0-9a-f]{64}\z/;
    }
    my $inputs = $manifest->{inputs};
    die "Acceptance manifest inputs are missing\n" unless ref $inputs eq 'HASH';
    for my $name (qw(source perl5 jperl jcpan jar sbom)) {
        die "Acceptance manifest input $name is missing\n"
            unless ref $inputs->{$name} eq 'HASH' && length($inputs->{$name}{path} // '');
    }
    die "Manifest jperl hash differs from identity\n"
        unless ($inputs->{jperl}{sha256} // '') eq $identity->{jperl_sha256};
    die "Manifest JAR hash differs from identity\n"
        unless ($inputs->{jar}{sha256} // '') eq $identity->{jar_sha256};
    die "Manifest SBOM hash differs from identity\n"
        unless ($inputs->{sbom}{sha256} // '') eq $identity->{sbom_sha256};
    return { map { $_ => $identity->{$_} }
        qw(source_commit runner_commit perl5_commit jperl_sha256 jar_sha256 sbom_sha256) };
}

sub validate_policy {
    my ($policy) = @_;
    die "Target policy schema_version must be 1\n"
        unless ($policy->{schema_version} // 0) == 1;
    die "Target policy expected_targets is empty\n"
        unless ref($policy->{expected_targets}) eq 'ARRAY' && @{$policy->{expected_targets}};
    die "Target policy targets is empty\n"
        unless ref($policy->{targets}) eq 'ARRAY' && @{$policy->{targets}};
    my (%expected, %actual);
    for my $name (@{$policy->{expected_targets}}) {
        die "Target policy has invalid or duplicate expected target\n"
            unless defined($name) && !ref($name) && length($name) && !$expected{$name}++;
    }
    for my $target (@{$policy->{targets}}) {
        die "Target policy entry is malformed\n" unless ref $target eq 'HASH';
        my $name = $target->{name} // '';
        die "Target policy has invalid or duplicate target entry\n"
            unless length($name) && !$actual{$name}++;
        die "Target $name has no rationale\n" unless length($target->{rationale} // '');
        die "Target $name has invalid timeout\n"
            unless ($target->{timeout_seconds} // 0) =~ /\A\d+\z/
                && $target->{timeout_seconds} > 0;
        die "Target $name has no required modes\n"
            unless ref($target->{required_modes}) eq 'ARRAY' && @{$target->{required_modes}};
        my %mode;
        for (@{$target->{required_modes}}) {
            die "Target $name has invalid or duplicate mode\n"
                unless /\A(?:jvm|interpreter)\z/ && !$mode{$_}++;
        }
        die "Target $name must require JVM and interpreter\n"
            unless canonical([sort keys %mode]) eq canonical([qw(interpreter jvm)]);
        die "Target $name focused selector policy is missing\n"
            unless JSON::PP::is_bool($target->{focused_selector_permitted});
        die "Target $name approved warnings must be an array\n"
            unless ref($target->{approved_warning_patterns}) eq 'ARRAY';
        eval { qr/$_/ for @{$target->{approved_warning_patterns}} };
        die "Target $name has invalid approved warning pattern\n" if $@;
    }
    die "Target policy expected/result set drift\n"
        unless canonical([sort keys %expected]) eq canonical([sort keys %actual]);
}

sub protected_inputs {
    my ($manifest, $policy, $inputs) = @_;
    my %files = (manifest => $manifest, policy => $policy,
        jperl => $inputs->{jperl}{path}, jcpan => $inputs->{jcpan}{path},
        jar => $inputs->{jar}{path}, sbom => $inputs->{sbom}{path});
    my %protected;
    for my $name (keys %files) {
        die "Protected input is missing or empty: $files{$name}\n"
            unless -f $files{$name} && -s $files{$name};
        $protected{$name} = { path => abs_path($files{$name}), sha256 => sha256_file($files{$name}) };
    }
    for my $pair ([jperl => 'jperl'], [jar => 'jar'], [sbom => 'sbom']) {
        my ($name, $input) = @$pair;
        die "Protected $name hash differs from manifest\n"
            unless $protected{$name}{sha256} eq $inputs->{$input}{sha256};
    }
    die "Protected jcpan hash differs from manifest\n"
        unless $protected{jcpan}{sha256} eq ($inputs->{jcpan}{sha256} // '');
    return %protected;
}

sub verify_protected {
    my ($protected) = @_;
    for my $name (sort keys %$protected) {
        my $item = $protected->{$name};
        die "Protected input disappeared during execution: $name\n"
            unless -f $item->{path} && !-l $item->{path};
        if (exists $item->{dev}) {
            my @now = lstat($item->{path});
            die "Protected input identity mutated during execution: $name\n"
                unless @now && $now[0] == $item->{dev}
                    && $now[1] == $item->{inode} && $now[2] == $item->{mode}
                    && $now[7] == $item->{size} && $now[9] == $item->{mtime};
        }
        die "Protected input mutated during execution: $name\n"
            unless sha256_file($item->{path}) eq $item->{sha256};
    }
}

sub verify_checkout {
    my ($descriptor, $expected, $label, $git) = @_;
    die "$label checkout descriptor commit differs from identity\n"
        unless ($descriptor->{commit} // '') eq $expected;
    my $actual = capture([$git, '-C', $descriptor->{path}, 'rev-parse', 'HEAD']);
    $actual =~ s/\s+\z//;
    die "$label checkout commit mismatch\n" unless $actual eq $expected;
    my $status = capture([$git, '-C', $descriptor->{path}, 'status', '--porcelain', '--untracked-files=no']);
    die "$label checkout tracked state is dirty\n" if length $status;
}

sub analyze_log {
    my ($log, $target_policy) = @_;
    my $text = read_raw($log);
    my @summaries;
    while ($text =~ /^Files=(\d+),\s+Tests=(\d+)\b[^\r\n]*(?:\r?\n|\z)/mg) {
        push @summaries, {
            files => 0 + $1,
            tests => 0 + $2,
            start => $-[0],
            end => $+[0],
        };
    }
    my $tests = @summaries ? $summaries[-1]{tests} : 0;
    my @not_ok = $text =~ /^\s*not ok\b[^\r\n]*$/mg;
    my $failures = grep { $_ !~ /#\s*TODO\b/i } @not_ok;
    my $skips = () = $text =~ /^\s*ok\b[^\n]*#\s*skip\b/img;
    my @warnings = grep {
        my $line = $_;
        $line !~ /^\s*(?:ok|not ok|#)/i
            && $line =~ /(?:Use of uninitialized|uninitialized value|Argument .* isn't numeric|Possible unintended interpolation|Wide character in|Subroutine .* redefined|WARNING:|warning:|\bat\s+\S.*\s+line\s+\d+\.?\s*$)/i
    } split /\n/, $text;
    my @approved = @{$target_policy->{approved_warning_patterns}};
    my @unapproved = grep {
        my $line = $_;
        !grep { $line =~ /$_/ } @approved;
    } @warnings;
    my $integrity = analyze_final_tap_scope($text, \@summaries);
    my $truncated = $integrity->{has_evidence} && !$integrity->{complete}
        ? 1 : 0;
    my $malformed = !@summaries || !$integrity->{complete}
        || $text =~ /(?:Parse errors|Bad plan|No plan found|Tests out of sequence|Test Summary Report|\bDubious,\s+test returned|\bResult:\s*FAIL\b|Looks like you failed|Failed \d+\/\d+ subtests|No subtests run)/i
        ? 1 : 0;
    return { total_tests => $tests, failures => $failures, skips => $skips,
        zero_tap => $tests == 0 ? 1 : 0, malformed => $malformed,
        truncated => $truncated, warning_diagnostics => \@warnings,
        unapproved_warnings => \@unapproved };
}

sub analyze_final_tap_scope {
    my ($text, $summaries) = @_;
    return { complete => 0, has_evidence => 0 } unless @$summaries;
    my $summary = $summaries->[-1];
    my $scope_start = @$summaries > 1 ? $summaries->[-2]{end} : 0;
    my $scope = substr($text, $scope_start,
        $summary->{start} - $scope_start);
    my $success_adjacent = $scope =~
        /(?:\A|\r?\n)All tests successful\.[ \t]*\r?\n?\z/;

    my $harness = analyze_harness_file_results($scope);
    if ($harness->{has_evidence}) {
        my $result_adjacent = substr($text, $summary->{end}) =~
            /\AResult:[ \t]*PASS[ \t]*(?:\r?\n|\z)/;
        return {
            complete => $success_adjacent && $result_adjacent
                && $harness->{complete} && $summary->{files} > 0
                && $harness->{file_count} == $summary->{files} ? 1 : 0,
            has_evidence => 1,
        };
    }

    my @tap = $scope =~ /^(?:not )?ok\b[^\r\n]*$/mg;
    my @plans = $scope =~ /^1\.\.(\d+)\b[^\r\n]*$/mg;
    my $has_evidence = @tap || @plans ? 1 : 0;
    my $complete = @plans == 1 && $plans[0] > 0
        && @tap == $plans[0] && $plans[0] == $summary->{tests}
        && $summary->{files} > 0
        && $success_adjacent;
    return { complete => $complete ? 1 : 0, has_evidence => $has_evidence };
}

sub analyze_harness_file_results {
    my ($scope) = @_;
    my (%seen, @paths);
    my ($pending, $completed, $duplicate, $incomplete) =
        (undef, 0, 0, 0);
    for my $line (split /\r?\n/, $scope, -1) {
        if ($line =~ /\A(\S.*?\.t)[ \t]+\.{2,}(?:[ \t]+(ok|skipped(?::[^\r\n]*)?))?[ \t]*\z/i) {
            $incomplete = 1 if defined $pending;
            my ($path, $status) = ($1, $2);
            next unless is_harness_test_path($path);
            my $identity = normalize_harness_test_path($path);
            $duplicate = 1 if $seen{$identity}++;
            push @paths, $identity;
            if (defined $status) {
                ++$completed;
                $pending = undef;
            }
            else {
                $pending = $identity;
            }
            next;
        }
        if (defined $pending
                && $line =~ /\A(?:ok|skipped(?::[^\r\n]*)?)[ \t]*\z/i) {
            ++$completed;
            $pending = undef;
        }
    }
    return {
        has_evidence => @paths ? 1 : 0,
        complete => @paths && !defined($pending) && !$duplicate && !$incomplete
            && $completed == @paths ? 1 : 0,
        file_count => scalar @paths,
    };
}

sub is_harness_test_path {
    my ($path) = @_;
    $path =~ s/\A[ \t]+|[ \t]+\z//g;
    return 0 if $path =~ /[\x00-\x1f\x7f]/;
    return 1 if $path =~ m{\A(?:[A-Za-z]:[\\/]|[\\/]|\.\.?[\\/])};
    return 1 if $path =~ m{[\\/]};
    return $path =~ /\A[^\s\\\/]+\.t\z/i ? 1 : 0;
}

sub normalize_harness_test_path {
    my ($path) = @_;
    $path =~ s/\A[ \t]+|[ \t]+\z//g;
    $path =~ tr{\\}{/};
    $path =~ s{\A\./}{};
    $path =~ s{/+}{/}g;
    $path =~ s{\A([A-Z]):}{lc($1) . ':'}e;
    return $path;
}

sub run_child {
    my (%arg) = @_;
    my $started = time;
    my $started_at = timestamp();
    my $pid = fork();
    die "Cannot fork $arg{argv}[0]: $!\n" unless defined $pid;
    if ($pid == 0) {
        eval { setpgid(0, 0) };
        open STDOUT, '>:raw', $arg{log} or die "Cannot write $arg{log}: $!\n";
        open STDERR, '>&', \*STDOUT or die "Cannot redirect stderr: $!\n";
        for my $key (keys %{$arg{environment} // {}}) {
            defined $arg{environment}{$key}
                ? ($ENV{$key} = $arg{environment}{$key}) : delete $ENV{$key};
        }
        exec { $arg{argv}[0] } @{$arg{argv}} or do {
            print STDERR "Cannot execute $arg{argv}[0]: $!\n";
            POSIX::_exit(255);
        };
    }
    my $parent_group_ready = eval { setpgid($pid, $pid); 1 } ? 1 : 0;
    my ($raw, $timed_out);
    my $completed = eval {
        local $SIG{ALRM} = sub { die "timeout\n" };
        alarm $arg{timeout};
        waitpid($pid, 0);
        $raw = $?;
        alarm 0;
        1;
    };
    if (!$completed) {
        alarm 0;
        $timed_out = 1;
        my $group = eval { getpgrp($pid) };
        my $owns_group = defined($group) && $group == $pid;
        $owns_group = 1 if !$owns_group && $parent_group_ready;
        my $kill_target = $owns_group ? -$pid : $pid;
        kill 'TERM', $kill_target;
        select undef, undef, undef, 0.1;
        kill 'KILL', $kill_target if kill 0, $pid;
        waitpid($pid, 0);
        $raw = $?;
    }
    my $signal = $raw & 127;
    my $exit = $signal ? 0 : ($raw >> 8);
    return { started_at => $started_at, ended_at => timestamp(),
        duration_seconds => 0 + sprintf('%.6f', time - $started),
        exit_code => $exit, signal => $signal, timeout => $timed_out ? 1 : 0 };
}

sub resume_existing {
    my ($output, $policy, $identity, $inputs, $evidence, $protected) = @_;
    die "Safe resume requires retained cpan-acceptance.json\n" unless -f $output;
    my $seal = "$output.sha256";
    die "Safe resume requires retained cpan-acceptance.json.sha256\n" unless -f $seal;
    my $seal_text = read_raw($seal);
    my ($sealed_sha) = $seal_text =~ /\A([0-9a-f]{64})\b/;
    die "Retained acceptance manifest seal is malformed\n" unless $sealed_sha;
    die "Retained acceptance manifest hash mismatch\n"
        unless sha256_file($output) eq $sealed_sha;
    my $old = load_json($output, 'retained CPAN acceptance');
    die "Retained target policy drift\n"
        unless canonical($old->{expected_targets}) eq canonical($policy->{expected_targets});
    for my $field (qw(source_commit runner_commit perl5_commit jperl_sha256 jar_sha256 sbom_sha256)) {
        die "Retained identity drift: $field\n"
            unless ($old->{identity}{$field} // '') eq ($identity->{$field} // '');
    }
    if (exists $identity->{authority_tuple_sha256}) {
        for my $field (sort keys %$identity) {
            die "Retained authority identity drift: $field\n"
                unless ($old->{identity}{$field} // '') eq ($identity->{$field} // '');
        }
        my $authority = $old->{authority};
        die "Retained authority envelope is missing or malformed\n"
            unless ref($authority) eq 'HASH'
                && canonical([sort keys %$authority]) eq canonical([sort qw(schema
                    execution_authorized tuple_sha256 marker_sha256 bridge_sha256
                    launch_sha256 seal_sha256)])
                && ($authority->{schema} // '') eq
                    'perlonjava.phase36.cpan-launch-authority/v1'
                && JSON::PP::is_bool($authority->{execution_authorized})
                && $authority->{execution_authorized}
                && JSON::PP::is_bool($identity->{execution_authorized})
                && $identity->{execution_authorized}
                && ($authority->{execution_authorized} ? 1 : 0)
                    == ($identity->{execution_authorized} ? 1 : 0)
                && ($authority->{tuple_sha256} // '') eq
                    $identity->{authority_tuple_sha256}
                && ($authority->{marker_sha256} // '') eq
                    $identity->{authority_marker_sha256}
                && ($authority->{bridge_sha256} // '') eq
                    $identity->{authority_bridge_sha256}
                && ($authority->{launch_sha256} // '') eq
                    $identity->{authority_launch_sha256}
                && ($authority->{seal_sha256} // '') eq
                    $identity->{authority_seal_sha256};
    }
    for my $field (qw(manifest policy jcpan)) {
        my $recorded = $old->{identity}{"${field}_sha256"} // '';
        die "Retained input identity drift: $field\n"
            unless $recorded eq $protected->{$field}{sha256};
    }
    die "Retained input descriptors drift\n"
        unless canonical($old->{identity}{inputs}) eq canonical($inputs);
    my %expected = ('jperl-version.log' => 'jperl-version');
    my %policy_by_name = map { $_->{name} => $_ } @{$policy->{targets}};
    for my $target (@{$policy->{expected_targets}}) {
        for my $mode (@{$policy_by_name{$target}{required_modes}}) {
            my $base = File::Spec->catfile('runs', slug("$target-$mode"));
            $expected{File::Spec->catfile($base, 'raw.log')} = 'raw-log';
            $expected{File::Spec->catfile($base, 'result.json')} = 'mode-result';
        }
    }
    my %retained;
    for my $artifact (@{$old->{artifacts} // []}) {
        die "Retained artifact descriptor is malformed\n"
            unless ref($artifact) eq 'HASH' && length($artifact->{path} // '')
                && ($artifact->{sha256} // '') =~ /\A[0-9a-f]{64}\z/;
        my $relative = $artifact->{path};
        die "Retained artifact path is unsafe: $relative\n"
            if File::Spec->file_name_is_absolute($relative)
                || grep { $_ eq '..' } File::Spec->splitdir($relative);
        die "Retained artifact is unexpected or has wrong kind: $relative\n"
            unless defined($expected{$relative})
                && ($artifact->{kind} // '') eq $expected{$relative}
                && !$retained{$relative}++;
        my $path = File::Spec->catfile($evidence, File::Spec->splitdir($relative));
        die "Retained artifact missing: $artifact->{path}\n" unless -f $path;
        my $resolved = abs_path($path);
        my $resolved_root = abs_path($evidence);
        die "Retained artifact resolves outside evidence root: $relative\n"
            unless defined($resolved) && defined($resolved_root)
                && index($resolved, "$resolved_root/") == 0;
        die "Retained artifact hash mismatch: $artifact->{path}\n"
            unless sha256_file($path) eq $artifact->{sha256};
    }
    my @missing = sort grep { !$retained{$_} } keys %expected;
    die "Retained artifact set is incomplete: @missing\n" if @missing;
    validate_retained_results($old, $policy, $identity, $inputs,
        $evidence, $protected);
    print "Safe resume verified retained evidence: $output\n";
    exit(($old->{status} // '') eq 'pass' ? 0 : 1);
}

sub validate_retained_results {
    my ($old, $policy, $identity, $inputs, $evidence, $protected) = @_;
    die "Retained result schema_version must be 2\n"
        unless ($old->{schema_version} // 0) == 2;
    my %policy_by_name = map { $_->{name} => $_ } @{$policy->{targets}};
    my $results = $old->{results};
    die "Retained result set drift\n" unless ref($results) eq 'HASH'
        && canonical([sort keys %$results])
            eq canonical([sort @{$policy->{expected_targets}}]);

    my $all_total = 0;
    my $all_pass = 1;
    for my $target (@{$policy->{expected_targets}}) {
        my $target_policy = $policy_by_name{$target};
        my $target_result = $results->{$target};
        die "Retained target result is malformed: $target\n"
            unless ref($target_result) eq 'HASH'
                && ref($target_result->{modes}) eq 'HASH';
        my @required_modes = sort @{$target_policy->{required_modes}};
        my @retained_modes = sort keys %{$target_result->{modes}};
        die "Retained mode set drift: $target\n"
            unless canonical(\@retained_modes) eq canonical(\@required_modes);

        my ($target_total, $target_pass, $target_timeout,
            $target_truncated, $target_execution_error) = (0, 1, 0, 0, 0);
        for my $mode (@required_modes) {
            my $base = File::Spec->catfile('runs', slug("$target-$mode"));
            my $raw_relative = File::Spec->catfile($base, 'raw.log');
            my $meta_relative = File::Spec->catfile($base, 'result.json');
            my $raw_path = File::Spec->catfile($evidence,
                File::Spec->splitdir($raw_relative));
            my $meta_path = File::Spec->catfile($evidence,
                File::Spec->splitdir($meta_relative));
            my $meta = load_json($meta_path, 'retained mode result');
            die "Retained mode result differs from aggregate: $target $mode\n"
                unless canonical($meta)
                    eq canonical($target_result->{modes}{$mode});
            die "Retained mode result identity mismatch: $target $mode\n"
                unless ($meta->{target} // '') eq $target
                    && ($meta->{mode} // '') eq $mode;

            my $argv = $meta->{argv};
            die "Retained mode command mismatch: $target $mode\n"
                unless ref($argv) eq 'ARRAY' && @$argv == 3
                    && same_path($argv->[0], $protected->{jcpan}{path})
                    && $argv->[1] eq '-t' && $argv->[2] eq $target;
            my $environment = $meta->{environment};
            my $mode_dir = File::Spec->catdir($evidence, $base);
            my $home = File::Spec->catdir($mode_dir, 'home');
            my $tmp = File::Spec->catdir($mode_dir, 'tmp');
            die "Retained mode environment mismatch: $target $mode\n"
                unless ref($environment) eq 'HASH'
                    && ($environment->{PERLONJAVA_JAR} // '') eq $inputs->{jar}{path}
                    && ($environment->{PERLONJAVA_HOME} // '') eq $home
                    && ($environment->{HOME} // '') eq $home
                    && ($environment->{TMPDIR} // '') eq $tmp
                    && ($environment->{PERL_MM_USE_DEFAULT} // '') eq '1'
                    && ($environment->{PHASE36_CPAN_TARGET} // '') eq $target
                    && ($environment->{PHASE36_CPAN_MODE} // '') eq $mode
                    && exists($environment->{JPERL_UNIMPLEMENTED})
                    && !defined($environment->{JPERL_UNIMPLEMENTED})
                    && ($mode eq 'interpreter'
                        ? (($environment->{JPERL_INTERPRETER} // '') eq '1')
                        : (exists($environment->{JPERL_INTERPRETER})
                            && !defined($environment->{JPERL_INTERPRETER})));
            my @environment_keys = qw(PERLONJAVA_JAR PERLONJAVA_HOME HOME TMPDIR
                PERL_MM_USE_DEFAULT JPERL_INTERPRETER JPERL_UNIMPLEMENTED
                PHASE36_CPAN_TARGET PHASE36_CPAN_MODE);
            die "Retained mode environment hash mismatch: $target $mode\n"
                unless ($meta->{environment_sha256} // '') eq
                    Digest::SHA::sha256_hex(canonical({ map {
                        $_ => $environment->{$_} } @environment_keys }));

            my $expected_mode_identity = { %$identity,
                jar_path => $inputs->{jar}{path},
                sbom_path => $inputs->{sbom}{path} };
            die "Retained mode artifact identity mismatch: $target $mode\n"
                unless canonical($meta->{identity})
                    eq canonical($expected_mode_identity);
            die "Retained raw log descriptor mismatch: $target $mode\n"
                unless ref($meta->{raw_log}) eq 'HASH'
                    && ($meta->{raw_log}{path} // '') eq $raw_relative
                    && ($meta->{raw_log}{sha256} // '') eq sha256_file($raw_path);

            my $analysis = analyze_log($raw_path, $target_policy);
            my $raw_text = read_raw($raw_path);
            my $execution_error = ($meta->{exit_code} // 0) == 255
                && $raw_text =~ /Cannot execute/ ? 1 : 0;
            my $passed = !$meta->{timeout} && !$meta->{signal}
                && ($meta->{exit_code} // -1) == 0 && !$execution_error
                && !$analysis->{zero_tap} && !$analysis->{malformed}
                && !$analysis->{truncated} && !$analysis->{failures}
                && !@{$analysis->{unapproved_warnings}};
            my $retained_analysis = {
                total_tests => $meta->{total_tests}, failures => $meta->{failures},
                skips => $meta->{skips}, zero_tap => $meta->{zero_tap},
                malformed => $meta->{malformed}, truncated => $meta->{truncated},
                warning_diagnostics => $meta->{warning_diagnostics},
                unapproved_warnings => $meta->{unapproved_warnings},
            };
            my $recomputed_analysis = {
                total_tests => $analysis->{total_tests}, failures => $analysis->{failures},
                skips => $analysis->{skips}, zero_tap => boolean($analysis->{zero_tap}),
                malformed => boolean($analysis->{malformed}),
                truncated => boolean($analysis->{truncated}),
                warning_diagnostics => $analysis->{warning_diagnostics},
                unapproved_warnings => $analysis->{unapproved_warnings},
            };
            die "Retained mode analysis mismatch: $target $mode\n"
                unless canonical($retained_analysis) eq canonical($recomputed_analysis)
                    && !!$meta->{execution_error} == !!$execution_error
                    && ($meta->{status} // '') eq ($passed ? 'pass' : 'fail');

            $target_total += $analysis->{total_tests};
            $target_pass = 0 unless $passed;
            $target_timeout ||= !!$meta->{timeout};
            $target_truncated ||= $analysis->{truncated} || $analysis->{malformed};
            $target_execution_error ||= $execution_error;
        }
        my $expected_target = {
            status => $target_pass ? 'pass' : 'fail',
            total_tests => $target_total,
            timeout => boolean($target_timeout),
            truncated => boolean($target_truncated),
            execution_error => boolean($target_execution_error),
            rationale => $target_policy->{rationale},
            focused_selector_permitted => boolean($target_policy->{focused_selector_permitted}),
            modes => $target_result->{modes},
        };
        die "Retained target aggregate mismatch: $target\n"
            unless canonical($target_result) eq canonical($expected_target);
        $all_total += $target_total;
        $all_pass = 0 unless $target_pass;
    }
    die "Retained aggregate analysis mismatch\n"
        unless ($old->{total_tests} // -1) == $all_total
            && ($old->{status} // '') eq ($all_pass ? 'pass' : 'fail');
}

sub directory_entries {
    my ($dir) = @_;
    opendir my $dh, $dir or die "Cannot read evidence directory $dir: $!\n";
    my @entries = grep { $_ ne '.' && $_ ne '..' } readdir $dh;
    closedir $dh;
    return @entries;
}
sub load_json { my ($p,$l)=@_; my $r=read_raw($p); my $d=eval{JSON::PP->new->utf8->decode($r)}; die "Invalid $l JSON in $p\n" unless ref($d) eq 'HASH'; return $d }
sub write_json { my ($p,$d)=@_; make_path(dirname($p)); open my $f,'>:raw',$p or die $!; print {$f} JSON::PP->new->canonical->pretty->encode($d); close $f or die $! }
sub write_raw { my ($p,$c)=@_; open my $f,'>:raw',$p or die "Cannot write $p: $!\n"; print {$f} $c; close $f or die "Cannot close $p: $!\n" }
sub read_raw { my ($p)=@_; open my $f,'<:raw',$p or die "Cannot read $p: $!\n"; local $/; my $r=<$f>; close $f; return $r }
sub sha256_file { my ($p)=@_; open my $f,'<:raw',$p or die $!; my $s=Digest::SHA->new(256); $s->addfile($f); close $f; return $s->hexdigest }
sub capture { capture_bounded($_[0], 1024 * 1024, 'trusted command') }
sub capture_bounded {
    my ($argv, $maximum, $label) = @_;
    open my $fh, '-|', @$argv or die "Cannot execute $argv->[0]: $!\n";
    binmode $fh, ':raw'; my $bytes = '';
    while (1) {
        my $read = read($fh, my $chunk, 8192);
        die "Cannot read $label: $!\n" unless defined $read;
        last unless $read;
        $bytes .= $chunk;
        die "$label output exceeds its bounded byte limit\n"
            if length($bytes) > $maximum;
    }
    close $fh or die "$label command failed\n";
    return $bytes;
}
sub canonical { JSON::PP->new->canonical->encode($_[0]) }
sub boolean { $_[0] ? JSON::PP::true : JSON::PP::false }
sub slug { my $s=lc $_[0]; $s =~ s/[^a-z0-9]+/-/g; $s =~ s/^-|-$//g; return $s }
sub same_path { (abs_path($_[0]) // '') eq (abs_path($_[1]) // '') }
sub relative_path { File::Spec->abs2rel($_[0], $_[1]) }
sub timestamp { strftime('%Y-%m-%dT%H:%M:%SZ', gmtime()) }
sub validate_cli_tokens {
    my ($argv) = @_;
    my %takes_value = map { $_ => 1 } qw(manifest authority-marker policy
        evidence-dir jcpan jperl version-timeout);
    my %flag = map { $_ => 1 } qw(prepare-only no-prepare-only resume no-resume help);
    my %seen;
    for (my $index = 0; $index < @$argv; ++$index) {
        my $token = $argv->[$index];
        die "Unknown option syntax: $token\n"
            unless $token =~ /\A--([^=]+)(?:=(.*))?\z/s;
        my ($name, $inline) = ($1, $2);
        die "Unknown option --$name\n" unless $takes_value{$name} || $flag{$name};
        my $canonical = $name =~ s/\Ano-//r;
        die "Duplicate option --$canonical\n" if $seen{$canonical}++;
        if ($takes_value{$name} && !defined $inline) {
            die "Option --$name requires a value\n"
                if $index + 1 >= @$argv || $argv->[$index + 1] =~ /\A--/;
            ++$index;
        }
        die "Option --$name does not take a value\n"
            if $flag{$name} && defined $inline;
    }
}
sub usage {
    print <<'USAGE';
Usage: run_phase36_cpan_acceptance.pl --authority-marker ABS --evidence-dir DIR [OPTIONS]

Run the immutable Phase 36 affected-CPAN target policy. Acceptance execution
takes only the canonical authority marker produced by
prepare_phase36_cpan_launch_manifest.pl and revalidates its launch, bridge,
seal, package/make producers, selected inputs, and actual JAR/SBOM bytes.

Options:
  --authority-marker  Canonical authoritative bundle marker (acceptance only)
  --policy FILE       Prepare-only policy override
  --resume            Verify and reuse a complete sealed evidence directory
  --prepare-only      Non-authoritative compatibility run; requires --manifest
                      and explicitly injected --jcpan/--jperl fake launchers
  --version-timeout N Bound the jperl -v identity probe (default 30 seconds)

Fake launchers may inspect PHASE36_CPAN_TARGET and PHASE36_CPAN_MODE. Every
child receives the manifest JAR through PERLONJAVA_JAR and an isolated HOME,
PERLONJAVA_HOME, and TMPDIR beneath the evidence directory.
USAGE
    exit $_[0];
}
