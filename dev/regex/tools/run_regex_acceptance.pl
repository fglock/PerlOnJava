#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path getcwd);
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Spec;
use Getopt::Long qw(Configure GetOptions);
use JSON::PP;

my $MAX_RAW_TAP_BYTES = 64 * 1024 * 1024;
my $MAX_RAW_TAP_AGGREGATE_BYTES = 4 * 1024 * 1024 * 1024;
my $MAX_RAW_TAP_FILES = 100_000;
my %option = (
    perl => $^X,
    ledger_tool => 'dev/regex/tools/generate_regex_test_ledger.pl',
    ledger_scope => 'complete',
    runner_tool => 'dev/tools/perl_test_runner.pl',
    comparator_tool => 'dev/tools/compare_test_results.pl',
    packaging_tool => 'dev/regex/tools/verify-joni-packaging.pl',
    jperl => './jperl',
    timeout => 300,
    version_timeout => 30,
    jobs => 5,
    cpu_heavy_jobs => 2,
);
my $help;
Configure(qw(no_auto_abbrev no_ignore_case no_getopt_compat));
reject_duplicate_options(\@ARGV);
GetOptions(
    'baseline=s' => \$option{baseline},
    'artifact-dir=s' => \$option{artifact_dir},
    'jar=s' => \$option{jar},
    'sbom=s' => \$option{sbom},
    'package-evidence=s' => \$option{package_evidence},
    'make-evidence=s' => \$option{make_evidence},
    'perl=s' => \$option{perl},
    'source-dir=s' => \$option{source_dir},
    'perl5-dir=s' => \$option{perl5_dir},
    'jperl=s' => \$option{jperl},
    'timeout=i' => \$option{timeout},
    'version-timeout=i' => \$option{version_timeout},
    'jobs=i' => \$option{jobs},
    'cpu-heavy-jobs=i' => \$option{cpu_heavy_jobs},
    'ledger-tool=s' => \$option{ledger_tool},
    'ledger-scope=s' => \$option{ledger_scope},
    'runner-tool=s' => \$option{runner_tool},
    'comparator-tool=s' => \$option{comparator_tool},
    'packaging-tool=s' => \$option{packaging_tool},
    'prepare-only!' => \$option{prepare_only},
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;

if ($option{prepare_only}) {
    my @production_defaults = grep {
        ($option{$_} // '') eq {
            jperl => './jperl',
            ledger_tool => 'dev/regex/tools/generate_regex_test_ledger.pl',
            runner_tool => 'dev/tools/perl_test_runner.pl',
            comparator_tool => 'dev/tools/compare_test_results.pl',
            packaging_tool => 'dev/regex/tools/verify-joni-packaging.pl',
        }->{$_}
    } qw(jperl ledger_tool runner_tool comparator_tool packaging_tool);
    die "--prepare-only requires injected non-production tools for: "
        . join(', ', @production_defaults) . "\n"
        if @production_defaults;
}

for my $required (qw(baseline artifact_dir jar sbom)) {
    die "--$required is required\n" unless defined $option{$required}
        && length $option{$required};
}
if (!$option{prepare_only}
        || defined($option{package_evidence}) || defined($option{make_evidence})) {
    for my $required (qw(package_evidence make_evidence)) {
        die "--" . ($required =~ s/_/-/gr) . " is required\n"
            unless defined $option{$required} && length $option{$required};
    }
}
die "--timeout must be positive\n" unless $option{timeout} > 0;
die "--version-timeout must be positive\n" unless $option{version_timeout} > 0;
die "--jobs must be positive\n" unless $option{jobs} > 0;
die "--cpu-heavy-jobs must be between 1 and 3\n"
    unless $option{cpu_heavy_jobs} >= 1 && $option{cpu_heavy_jobs} <= 3;
die "--ledger-scope must be regex or complete\n"
    unless $option{ledger_scope} =~ /\A(?:regex|complete)\z/;

my $root = $option{source_dir} // getcwd();
$option{perl5_dir} //= File::Spec->catdir($root, 'perl5');
validate_file($option{baseline}, 'acceptance baseline');
validate_file($option{jar}, 'standalone JAR');
validate_file($option{sbom}, 'SBOM');
validate_directory($option{artifact_dir}, 'artifact directory');
for my $tool (qw(ledger_tool runner_tool comparator_tool packaging_tool)) {
    validate_file($option{$tool}, "$tool path");
}
validate_program($option{perl}, 'Perl executable');
validate_program($option{jperl}, 'jperl executable');

for my $key (qw(baseline jar sbom jperl)) {
    $option{$key} = abs_file($option{$key});
}
for my $key (qw(package_evidence make_evidence)) {
    next unless defined $option{$key};
    $option{$key} = strict_evidence_file($option{$key},
        ($key =~ s/_/ /gr));
}
$option{make_evidence_seal} = strict_evidence_file(
    "$option{make_evidence}.seal", 'make evidence seal')
    if defined $option{make_evidence};

my $start_sha = git_sha($root);
my $start_state = tracked_state($root);
my $perl5_sha = git_sha($option{perl5_dir});
my %input_sha = map { $_ => sha256_file($option{$_}) } qw(baseline jar sbom jperl);
for my $key (qw(package_evidence make_evidence make_evidence_seal)) {
    $input_sha{$key} = sha256_file($option{$key}) if defined $option{$key};
}
my $release_authority = defined($option{package_evidence})
    ? validate_release_authority(\%option, \%input_sha, $start_sha, $root)
    : {
        schema_version => 1,
        kind => 'regex_implementation-release-authority',
        authoritative => JSON::PP::false,
        mode => 'prepare-only-without-release-evidence',
    };
my %path = map { $_ => File::Spec->catfile($option{artifact_dir}, $_) } qw(
    regex-ledger.json
    regex-files.txt
    strict-regex-ledger.json
    regex-scope-files.txt
    strict-regex-files.txt
    jvm-results.json
    interpreter-results.json
    jvm-comparison.json
    interpreter-comparison.json
    jvm-strict-regex-comparison.json
    interpreter-strict-regex-comparison.json
    ledger.log
    strict-regex-ledger.log
    jvm-runner.log
    interpreter-runner.log
    jvm-comparison.log
    interpreter-comparison.log
    jvm-strict-regex-comparison.log
    interpreter-strict-regex-comparison.log
    raw-tap-index.json
    packaging.log
    jperl-version.log
    manifest.json
);
for my $name (keys %path) {
    die "Refusing to overwrite retained artifact $path{$name}\n" if -e $path{$name};
}

my @commands;
my %statuses;
run_logged(
    name => 'jperl-version', command => [$option{jperl}, '-v'],
    log => $path{'jperl-version.log'}, commands => \@commands, statuses => \%statuses,
    timeout => $option{version_timeout}, environment => {
        JPERL_UNIMPLEMENTED => undef,
        PERLONJAVA_JAR => $option{jar},
    },
);
my $runner_sha = parse_runner_sha($path{'jperl-version.log'}, $start_sha);
run_logged(
    name => 'ledger',
    command => [$option{perl}, $option{ledger_tool},
        '--scope', $option{ledger_scope},
        '--runner-list', $path{'regex-files.txt'}, '--output', $path{'regex-ledger.json'}],
    log => $path{'ledger.log'},
    commands => \@commands, statuses => \%statuses,
);

my $ledger = load_json($path{'regex-ledger.json'}, 'ledger');
die "Ledger has unresolved references\n" if ($ledger->{summary}{unresolved_references} // 0) != 0;
my @files = load_file_list($path{'regex-files.txt'});
die "Ledger runner list is empty\n" unless @files;
my $expected_files = scalar @files;
my ($strict_regex_ledger, @strict_regex_files);
if ($option{ledger_scope} eq 'complete') {
    run_logged(
        name => 'strict-regex-ledger', command => [$option{perl}, $option{ledger_tool},
            '--scope', 'regex', '--runner-list', $path{'regex-scope-files.txt'},
            '--output', $path{'strict-regex-ledger.json'}],
        log => $path{'strict-regex-ledger.log'},
        commands => \@commands, statuses => \%statuses,
    );
    $strict_regex_ledger = load_json($path{'strict-regex-ledger.json'},
        'strict regex ledger');
    die "Strict regex ledger has unresolved references\n"
        if ($strict_regex_ledger->{summary}{unresolved_references} // 0) != 0;
} else {
    $strict_regex_ledger = $ledger;
}
@strict_regex_files = strict_semantic_files($strict_regex_ledger);
die "Strict regex semantic list is empty\n" unless @strict_regex_files;
write_file_list($path{'strict-regex-files.txt'}, \@strict_regex_files);
my %complete = map { $_ => 1 } @files;
my @outside = grep { !$complete{$_} } @strict_regex_files;
die "Strict regex semantic list is not a subset of the runner ledger: @outside\n"
    if @outside;
my $strict_regex_expected_files = scalar @strict_regex_files;

my @runner_common = ($option{perl}, $option{runner_tool},
    '--jperl', $option{jperl}, '--timeout', $option{timeout},
    '--jobs', $option{jobs}, '--cpu-heavy-jobs', $option{cpu_heavy_jobs});
run_logged(
    name => 'jvm-runner',
    command => [@runner_common, '--output', $path{'jvm-results.json'}, @files],
    log => $path{'jvm-runner.log'},
    environment => {
        JPERL_INTERPRETER => undef,
        JPERL_UNIMPLEMENTED => undef,
        PERLONJAVA_JAR => $option{jar},
    },
    commands => \@commands, statuses => \%statuses,
);
run_logged(
    name => 'interpreter-runner',
    command => [@runner_common, '--output', $path{'interpreter-results.json'}, @files],
    log => $path{'interpreter-runner.log'},
    environment => {
        JPERL_INTERPRETER => 1,
        JPERL_UNIMPLEMENTED => undef,
        PERLONJAVA_JAR => $option{jar},
    },
    commands => \@commands, statuses => \%statuses,
);

my $raw_tap_index = retain_raw_tap_evidence(
    $option{artifact_dir}, \@files,
    jvm => $path{'jvm-results.json'},
    interpreter => $path{'interpreter-results.json'},
);
write_json($path{'raw-tap-index.json'}, $raw_tap_index);
die "Acceptance runner evidence has no raw TAP rows\n"
    unless @{$raw_tap_index->{entries}};

for my $leg (
    ['jvm', $path{'jvm-results.json'}, $path{'jvm-comparison.json'}],
    ['interpreter', $path{'interpreter-results.json'}, $path{'interpreter-comparison.json'}],
) {
    run_logged(
        name => "$leg->[0]-comparison",
        command => [$option{perl}, $option{comparator_tool},
            '--fail-on-regression', '--fail-on-new-invalid',
            '--require-file-identity',
            '--expected-files', $expected_files,
            '--file-list', $path{'regex-files.txt'}, '--output', $leg->[2],
            $option{baseline}, $leg->[1]],
        log => $path{"$leg->[0]-comparison.log"},
        commands => \@commands, statuses => \%statuses,
    );
    verify_comparison($leg->[2], $expected_files, 1, \@files,
        $path{"$leg->[0]-comparison.log"});

    my $strict_output = $path{"$leg->[0]-strict-regex-comparison.json"};
    run_logged(
        name => "$leg->[0]-strict-regex-comparison",
        command => [$option{perl}, $option{comparator_tool},
            '--fail-on-regression', '--fail-on-invalid',
            '--require-file-identity',
            '--expected-files', $strict_regex_expected_files,
            '--file-list', $path{'strict-regex-files.txt'},
            '--output', $strict_output, $option{baseline}, $leg->[1]],
        log => $path{"$leg->[0]-strict-regex-comparison.log"},
        commands => \@commands, statuses => \%statuses,
    );
    verify_comparison($strict_output, $strict_regex_expected_files, 0,
        \@strict_regex_files,
        $path{"$leg->[0]-strict-regex-comparison.log"});
}
run_logged(
    name => 'packaging',
    command => [$option{perl}, $option{packaging_tool}, '--strict',
        $option{jar}, $option{sbom}],
    log => $path{'packaging.log'}, commands => \@commands, statuses => \%statuses,
);

my $final_sha = git_sha($root);
die "Checkout HEAD changed during acceptance: $start_sha -> $final_sha\n"
    unless $start_sha eq $final_sha;
die "Tracked source state changed during acceptance\n"
    unless tracked_state($root) eq $start_state;
for my $key (keys %input_sha) {
    die "Acceptance input changed during execution: $key\n"
        unless sha256_file($option{$key}) eq $input_sha{$key};
}
if (defined $option{package_evidence}) {
    my $revalidated = validate_release_authority(
        \%option, \%input_sha, $start_sha, $root);
    die "Release authority changed during acceptance\n"
        unless JSON::PP->new->canonical->utf8->encode($revalidated)
            eq JSON::PP->new->canonical->utf8->encode($release_authority);
}
revalidate_raw_tap_evidence($option{artifact_dir}, $raw_tap_index,
    jvm => $path{'jvm-results.json'},
    interpreter => $path{'interpreter-results.json'});

my @retained = sort grep { -f $path{$_} } keys %path;
my $manifest = {
    schema_version => 1,
    mode => $option{prepare_only} ? 'prepare-only' : 'acceptance',
    source => {
        starting_sha => $start_sha,
        final_sha => $final_sha,
        perl5_sha_as_provenance => $perl5_sha,
        tracked_state_signature => $start_state,
    },
    identity => {
        source_commit => $start_sha,
        runner_commit => $runner_sha,
        perl5_commit => $perl5_sha,
        launcher => { path => $option{jperl}, sha256 => $input_sha{jperl} },
        jar => { path => $option{jar}, sha256 => $input_sha{jar} },
        sbom => { path => $option{sbom}, sha256 => $input_sha{sbom} },
        baseline => { path => $option{baseline}, sha256 => $input_sha{baseline} },
        runner_policy => {
            timeout => $option{timeout},
            jobs => $option{jobs},
            cpu_heavy_jobs => $option{cpu_heavy_jobs},
        },
    },
    release_authority => $release_authority,
    baseline => abs_file($option{baseline}),
    artifact_directory => abs_path($option{artifact_dir}),
    expected_files => $expected_files,
    strict_regex_expected_files => $strict_regex_expected_files,
    verified_runner_sha => $runner_sha,
    ledger_summary => $ledger->{summary},
    strict_regex_ledger_summary => $strict_regex_ledger->{summary},
    compared_files => {
        complete => comparison_identity(\@files),
        strict_regex => comparison_identity(\@strict_regex_files),
    },
    raw_tap => $raw_tap_index,
    commands => \@commands,
    exit_statuses => \%statuses,
    artifacts => { map { $_ => { path => abs_file($path{$_}), sha256 => sha256_file($path{$_}) } } @retained },
};
write_json($path{'manifest.json'}, $manifest);
print "Regex implementation regex acceptance manifest: $path{'manifest.json'}\n";

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: run_regex_acceptance.pl --baseline FILE --artifact-dir DIR --jar FILE --sbom FILE --package-evidence FILE --make-evidence FILE [OPTIONS]

Compose the Regex implementation ledger, runner, comparator, and packaging gates into one
fail-closed immutable-artifact acceptance record. The current perl5 checkout is
recorded as provenance only, never compared to a pinned revision.

Options:
  --prepare-only             Execute the composition with explicitly injected
                             non-production tools and label it non-authoritative;
                             production defaults are rejected in this mode.
  --package-evidence FILE    Accepted absolute package-evidence bridge
  --make-evidence FILE       Accepted absolute warning-free make evidence
  --perl PATH --jperl PATH   Tool executables (default perl / ./jperl)
  --source-dir DIR           Clean source checkout to verify (default cwd)
  --perl5-dir DIR            Current imported perl5 checkout (default ./perl5)
  --timeout N --jobs N       Existing runner bounds and worker budget
  --cpu-heavy-jobs N         CPU-heavy runner lane budget (default 2; maximum 3)
  --version-timeout N        Hard bound for the jperl identity probe (default 30)
  --ledger-tool PATH --runner-tool PATH --comparator-tool PATH
  --ledger-scope MODE         complete (default) or regex-only discovery
  --packaging-tool PATH      Injectable list-form subprocess tools for testing
USAGE
    exit $status;
}

sub reject_duplicate_options {
    my ($arguments) = @_;
    my %seen;
    for my $argument (@$arguments) {
        next unless $argument =~ /\A--([^=]+)(?:=|\z)/;
        my $name = $1;
        $name = 'prepare-only' if $name eq 'no-prepare-only';
        die "Duplicate option --$name\n" if $seen{$name}++;
    }
}

sub validate_release_authority {
    my ($option, $input_sha, $source_commit, $source_root) = @_;
    my ($package, $package_bytes) = load_canonical_evidence(
        $option->{package_evidence}, 'package evidence');
    die "Package evidence changed before authority validation\n"
        unless sha256_hex($package_bytes) eq $input_sha->{package_evidence};
    validate_package_evidence($package, $option->{package_evidence});
    my ($make, $make_bytes) = load_canonical_evidence(
        $option->{make_evidence}, 'make evidence');
    die "Make evidence changed before authority validation\n"
        unless sha256_hex($make_bytes) eq $input_sha->{make_evidence};
    my $make_seal = validate_make_evidence($make, $make_bytes,
        $option->{make_evidence}, $option->{make_evidence_seal});
    die "Make evidence seal changed before authority validation\n"
        unless $make_seal->{sha256} eq $input_sha->{make_evidence_seal};

    my $jar_sha = $input_sha->{jar};
    my $sbom_sha = $input_sha->{sbom};
    die "Package evidence source commit differs from selected source\n"
        unless $package->{identity}{source_commit} eq $source_commit;
    die "Make evidence source commit differs from selected source\n"
        unless $make->{identity}{source_commit} eq $source_commit;
    die "Make evidence source root differs from raw --source-dir input\n"
        unless $make->{source}{root} eq abs_path($source_root);
    die "Make evidence runner commit differs from selected source\n"
        unless $make->{identity}{runner_commit} eq $source_commit;
    die "Make runtime JAR commit differs from selected source\n"
        unless $make->{identity}{jar_reported_commit} eq $source_commit;
    die "Make embedded JAR commit differs from selected source\n"
        unless $make->{identity}{jar_embedded_commit} eq $source_commit;
    die "Package and make evidence source identities disagree\n"
        unless $package->{identity}{source_commit}
            eq $make->{identity}{source_commit};
    die "Package evidence JAR differs from raw --jar input\n"
        unless $package->{identity}{jar_sha256} eq $jar_sha;
    die "Make evidence JAR differs from raw --jar input\n"
        unless $make->{identity}{jar_sha256} eq $jar_sha;
    die "Package evidence SBOM differs from raw --sbom input\n"
        unless $package->{identity}{sbom_sha256} eq $sbom_sha;
    die "Package and make evidence JAR identities disagree\n"
        unless $package->{identity}{jar_sha256}
            eq $make->{identity}{jar_sha256};
    die "Make evidence JAR artifact path differs from raw --jar input\n"
        unless $make->{artifacts}{jar}{path} eq $option->{jar};

    return {
        schema_version => 1,
        kind => 'regex_implementation-release-authority',
        authoritative => $option->{prepare_only}
            ? JSON::PP::false : JSON::PP::true,
        mode => $option->{prepare_only} ? 'prepare-only' : 'acceptance',
        package_evidence => {
            path => $option->{package_evidence},
            sha256 => sha256_hex($package_bytes),
            identity => { %{$package->{identity}} },
        },
        make_evidence => {
            path => $option->{make_evidence},
            sha256 => sha256_hex($make_bytes),
            seal => $make_seal,
            identity => { %{$make->{identity}} },
        },
        selected => {
            source_root => abs_path($source_root),
            source_commit => $source_commit,
            runner_commit => $make->{identity}{runner_commit},
            jar => { path => $option->{jar}, sha256 => $jar_sha },
            sbom => { path => $option->{sbom}, sha256 => $sbom_sha },
            baseline => {
                path => $option->{baseline}, sha256 => $input_sha->{baseline},
            },
        },
    };
}

sub validate_package_evidence {
    my ($document, $path) = @_;
    assert_exact_keys($document, 'package evidence', qw(artifacts completion
        duplicate_entries identity kind missing_entries producer schema_version
        verified));
    die "Package evidence is not the accepted authoritative bridge\n"
        unless ($document->{schema_version} // 0) == 1
            && ($document->{kind} // '') eq 'packaging'
            && ($document->{producer} // '') eq 'run_package_evidence.pl'
            && $document->{verified};
    die "Package evidence has missing or duplicate entries\n"
        unless ($document->{missing_entries} // -1) == 0
            && ($document->{duplicate_entries} // -1) == 0;
    assert_exact_keys($document->{identity}, 'package evidence identity',
        qw(jar_sha256 sbom_sha256 source_commit));
    assert_release_identity($document->{identity}, 1,
        'package evidence identity');
    assert_green_completion($document->{completion}, 'package evidence');
    assert_exact_keys($document->{artifacts}, 'package evidence artifacts',
        qw(deliverables logs notice_license report sbom_inputs));
    assert_exact_keys($document->{artifacts}{deliverables},
        'package evidence deliverables', qw(deb jar sbom));
    assert_exact_keys($document->{artifacts}{sbom_inputs},
        'package evidence SBOM inputs', qw(java_bom perl_bom));
    verify_package_descriptor($path, $document->{artifacts}{report},
        'package report');
    verify_package_descriptor($path, $document->{artifacts}{notice_license},
        'package notice/license evidence');
    for my $group (qw(deliverables sbom_inputs logs)) {
        die "Package evidence $group must be an object\n"
            unless ref($document->{artifacts}{$group}) eq 'HASH';
        for my $name (sort keys %{$document->{artifacts}{$group}}) {
            verify_package_descriptor($path,
                $document->{artifacts}{$group}{$name},
                "package $group $name");
        }
    }
    die "Retained package JAR identity mismatch\n"
        unless $document->{artifacts}{deliverables}{jar}{sha256}
            eq $document->{identity}{jar_sha256};
    die "Retained package SBOM identity mismatch\n"
        unless $document->{artifacts}{deliverables}{sbom}{sha256}
            eq $document->{identity}{sbom_sha256};
}

sub validate_make_evidence {
    my ($document, $bytes, $path, $seal_path) = @_;
    assert_exact_keys($document, 'make evidence', qw(artifacts authoritative
        command completion failure_scan identity inputs kind mode producer
        schema schema_version seal source status tools verified warning_scan));
    die "Make evidence is not authoritative acceptance evidence\n"
        unless ($document->{schema} // '')
                eq 'perlonjava.regex_implementation.make-evidence/v1'
            && ($document->{schema_version} // 0) == 1
            && ($document->{kind} // '') eq 'make'
            && ($document->{producer} // '') eq 'run_make_evidence.pl'
            && ($document->{mode} // '') eq 'acceptance'
            && ($document->{status} // '') eq 'pass'
            && $document->{verified} && $document->{authoritative};
    assert_release_identity($document->{identity}, 0,
        'make evidence identity');
    assert_green_completion($document->{completion}, 'make evidence');
    assert_exact_keys($document->{source}, 'make evidence source',
        qw(after before root));
    strict_absolute_directory($document->{source}{root}, 'make source root');
    for my $when (qw(before after)) {
        assert_exact_keys($document->{source}{$when}, "make source $when",
            qw(all_status_sha256 diff_sha256 extras head status_sha256
                tracked_clean));
        die "Make source $when is not clean or does not match its identity\n"
            unless $document->{source}{$when}{tracked_clean}
                && ($document->{source}{$when}{head} // '')
                    eq $document->{identity}{source_commit};
        assert_exact_keys($document->{source}{$when}{extras},
            "make source $when extras", qw(authority_inputs
                generated_file_count generated_paths generated_total_bytes));
    }
    assert_exact_keys($document->{command}, 'make evidence command',
        qw(argv cwd duration_milliseconds environment finished_utc started_utc));
    die "Make evidence command argv is malformed\n"
        unless ref($document->{command}{argv}) eq 'ARRAY'
            && @{$document->{command}{argv}} == 1;
    assert_exact_keys($document->{tools}, 'make evidence tools',
        qw(git jar_tool java make perl producer shell));
    for my $name (qw(git jar_tool java make perl shell)) {
        assert_exact_keys($document->{tools}{$name}, "make tool $name",
            qw(path sha256 size version_sha256));
    }
    assert_file_descriptor($document->{tools}{producer},
        'make evidence producer');
    assert_exact_keys($document->{inputs}, 'make evidence inputs',
        qw(build_gradle gradle_wrapper_jar gradle_wrapper_properties gradlew
            makefile settings_gradle));
    assert_file_descriptor($document->{inputs}{$_}, "make input $_")
        for keys %{$document->{inputs}};
    for my $scan (qw(warning_scan failure_scan)) {
        assert_exact_keys($document->{$scan}, "make $scan", qw(classifier
            classifier_sha256 complete_log_sha256 count matches));
        die "Make evidence $scan is not empty\n"
            unless ($document->{$scan}{count} // -1) == 0
                && ref($document->{$scan}{matches}) eq 'ARRAY'
                && !@{$document->{$scan}{matches}};
    }
    assert_exact_keys($document->{artifacts}, 'make evidence artifacts',
        qw(jar jar_embedded jar_version make_log source_after source_before
            tool_versions));
    for my $name (sort keys %{$document->{artifacts}}) {
        verify_absolute_descriptor($document->{artifacts}{$name},
            "make artifact $name");
    }
    die "Make JAR descriptor disagrees with make identity\n"
        unless $document->{artifacts}{jar}{sha256}
            eq $document->{identity}{jar_sha256};
    assert_exact_keys($document->{seal}, 'make evidence internal seal',
        qw(algorithm payload_sha256));
    die "Make evidence seal algorithm is not SHA-256\n"
        unless ($document->{seal}{algorithm} // '') eq 'SHA-256';
    my %payload = %$document;
    delete $payload{seal};
    my $payload_sha = sha256_hex(
        JSON::PP->new->canonical->utf8->encode(\%payload));
    die "Make evidence internal seal mismatch\n"
        unless ($document->{seal}{payload_sha256} // '') eq $payload_sha;
    my $seal_bytes = read_bounded_stable($seal_path, 512,
        'make evidence seal');
    my $expected = "SHA-256 $payload_sha " . sha256_hex($bytes) . "\n";
    die "Make evidence external seal mismatch\n" unless $seal_bytes eq $expected;
    return { path => $seal_path, sha256 => sha256_hex($seal_bytes) };
}

sub assert_release_identity {
    my ($identity, $package, $label) = @_;
    my @keys = $package
        ? qw(jar_sha256 sbom_sha256 source_commit)
        : qw(jar_embedded_commit jar_reported_commit jar_sha256 runner_commit
            source_commit);
    assert_exact_keys($identity, $label, @keys);
    die "$label source commit is malformed\n"
        unless ($identity->{source_commit} // '') =~ /\A[0-9a-f]{40}\z/;
    die "$label JAR SHA-256 is malformed\n"
        unless ($identity->{jar_sha256} // '') =~ /\A[0-9a-f]{64}\z/;
    if ($package) {
        die "$label SBOM SHA-256 is malformed\n"
            unless ($identity->{sbom_sha256} // '') =~ /\A[0-9a-f]{64}\z/;
    } else {
        for my $name (qw(runner_commit jar_reported_commit
                jar_embedded_commit)) {
            die "$label $name is malformed\n"
                unless ($identity->{$name} // '') =~ /\A[0-9a-f]{40}\z/;
        }
    }
}

sub assert_green_completion {
    my ($completion, $label) = @_;
    assert_exact_keys($completion, "$label completion",
        qw(exit_code incomplete review_stop signal timeout),
        ($label eq 'make evidence' ? 'truncated' : ()));
    die "$label completion is not independently accepted\n"
        unless ($completion->{exit_code} // -1) == 0
            && ($completion->{signal} // -1) == 0
            && !$completion->{timeout} && !$completion->{incomplete}
            && !$completion->{review_stop}
            && (!exists($completion->{truncated}) || !$completion->{truncated});
}

sub load_canonical_evidence {
    my ($path, $label) = @_;
    my $bytes = read_bounded_stable($path, 8 * 1024 * 1024, $label);
    my $document = eval { JSON::PP->new->utf8->decode($bytes) };
    die "Invalid $label JSON\n" unless $document && ref($document) eq 'HASH';
    my $canonical = JSON::PP->new->utf8->canonical->pretty->encode($document);
    die "$label is not exact canonical JSON or contains duplicate keys\n"
        unless $canonical eq $bytes;
    return ($document, $bytes);
}

sub verify_package_descriptor {
    my ($evidence, $descriptor, $label) = @_;
    assert_file_descriptor($descriptor, $label);
    my $relative = $descriptor->{path};
    die "$label path must be a safe relative retained path\n"
        if File::Spec->file_name_is_absolute($relative)
            || $relative eq '' || $relative =~ m{(?:\A|/)\.\.(?:/|\z)}
            || $relative =~ /\\/;
    my $path = strict_evidence_file(
        File::Spec->catfile(dirname($evidence), split m{/}, $relative), $label);
    verify_descriptor_bytes($path, $descriptor, $label);
}

sub verify_absolute_descriptor {
    my ($descriptor, $label) = @_;
    assert_file_descriptor($descriptor, $label);
    my $path = strict_evidence_file($descriptor->{path}, $label);
    verify_descriptor_bytes($path, $descriptor, $label);
}

sub verify_descriptor_bytes {
    my ($path, $descriptor, $label) = @_;
    my @before = lstat $path;
    die "$label disappeared before hashing\n" unless @before;
    die "$label size mismatch\n" unless $before[7] == $descriptor->{size};
    die "$label SHA-256 mismatch\n"
        unless sha256_file($path) eq $descriptor->{sha256};
    my @after = lstat $path;
    die "$label changed while hashing\n"
        unless @after && join(':', @before[0, 1, 7, 9, 10])
            eq join(':', @after[0, 1, 7, 9, 10]);
}

sub read_bounded_stable {
    my ($path, $maximum, $label) = @_;
    my @before = lstat $path;
    die "$label is missing, symlinked, or not a regular file\n"
        unless @before && -f _ && !-l _;
    die "$label exceeds bounded size of $maximum bytes\n"
        if $before[7] > $maximum;
    open my $fh, '<:raw', $path or die "Cannot read $label: $!\n";
    my $bytes = '';
    while (1) {
        my $count = read($fh, my $chunk, 65_536);
        die "Cannot read $label: $!\n" unless defined $count;
        last unless $count;
        $bytes .= $chunk;
        die "$label exceeds bounded size of $maximum bytes\n"
            if length($bytes) > $maximum;
    }
    close $fh or die "Cannot close $label: $!\n";
    my @after = lstat $path;
    die "$label changed while reading\n"
        unless @after && join(':', @before[0, 1, 7, 9, 10])
            eq join(':', @after[0, 1, 7, 9, 10]);
    return $bytes;
}

sub assert_file_descriptor {
    my ($descriptor, $label) = @_;
    assert_exact_keys($descriptor, $label, qw(path sha256 size));
    die "$label SHA-256 is malformed\n"
        unless ($descriptor->{sha256} // '') =~ /\A[0-9a-f]{64}\z/;
    die "$label size is malformed\n"
        unless defined($descriptor->{size})
            && "$descriptor->{size}" =~ /\A(?:0|[1-9][0-9]*)\z/;
}

sub assert_exact_keys {
    my ($value, $label, @expected) = @_;
    die "$label must be an object\n" unless ref($value) eq 'HASH';
    die "$label has an extra or missing field\n"
        unless join("\0", sort keys %$value)
            eq join("\0", sort @expected);
}

sub strict_evidence_file {
    my ($path, $label) = @_;
    die "$label path must be absolute and canonical\n"
        unless File::Spec->file_name_is_absolute($path)
            && File::Spec->canonpath($path) eq $path;
    reject_symlink_components($path, $label);
    die "$label is missing, symlinked, or not a regular file\n"
        unless -f $path && !-l $path;
    my $resolved = abs_path($path);
    die "$label path is not canonical\n"
        unless defined($resolved) && $resolved eq $path;
    return $path;
}

sub strict_absolute_directory {
    my ($path, $label) = @_;
    die "$label must be absolute and canonical\n"
        unless defined($path) && File::Spec->file_name_is_absolute($path)
            && File::Spec->canonpath($path) eq $path;
    reject_symlink_components($path, $label);
    my $resolved = abs_path($path);
    die "$label is missing or not canonical\n"
        unless defined($resolved) && -d $resolved && $resolved eq $path;
}

sub reject_symlink_components {
    my ($path, $label) = @_;
    my @parts = File::Spec->splitdir(File::Spec->canonpath($path));
    my $current = File::Spec->rootdir;
    for my $part (@parts) {
        next if $part eq '' || $part eq File::Spec->rootdir;
        $current = File::Spec->catfile($current, $part);
        die "$label path contains a symlink component\n" if -l $current;
    }
}

sub validate_file {
    my ($path, $label) = @_;
    die "$label is missing or empty: $path\n" unless -f $path && -s $path;
}

sub validate_directory {
    my ($path, $label) = @_;
    die "$label is missing: $path\n" unless -d $path;
}

sub validate_program {
    my ($program, $label) = @_;
    if ($program =~ m{/}) {
        die "$label is missing or not executable: $program\n" unless -x $program;
        return;
    }
    for my $directory (split /:/, ($ENV{PATH} // '')) {
        return if -x File::Spec->catfile($directory, $program);
    }
    die "$label is not on PATH: $program\n";
}

sub git_sha {
    my ($directory) = @_;
    die "Git checkout is missing: $directory\n" unless -d $directory;
    my $output = capture_command(['git', '-C', $directory, 'rev-parse', 'HEAD']);
    $output =~ s/\s+\z//;
    die "Cannot determine checkout HEAD for $directory\n" unless $output =~ /\A[0-9a-f]{40}\z/;
    return $output;
}

sub tracked_state {
    my ($directory) = @_;
    my $status = capture_command(['git', '-C', $directory, 'status', '--porcelain', '--untracked-files=no']);
    die "Tracked source checkout is not clean\n" if length $status;
    return sha256_hex(capture_command(['git', '-C', $directory, 'diff', '--binary', 'HEAD']));
}

sub parse_runner_sha {
    my ($log, $source_sha) = @_;
    my $contents = read_raw($log);
    my @sha = $contents =~ /\b([0-9a-f]{7,40})\b/ig;
    for my $candidate (@sha) {
        return $candidate if index($source_sha, lc $candidate) == 0;
    }
    die "jperl -v does not report the source Git SHA or prefix\n";
}

sub load_file_list {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "Cannot read runner list $file: $!\n";
    my @files;
    while (my $line = <$fh>) {
        $line =~ s/\r?\n\z//;
        next if $line =~ /^\s*(?:#|\z)/;
        die "Runner list contains an empty path\n" unless length $line;
        die "Runner list references missing test path: $line\n" unless -f $line;
        push @files, $line;
    }
    close $fh or die "Cannot close runner list $file: $!\n";
    return @files;
}

sub strict_semantic_files {
    my ($ledger) = @_;
    for my $key (qw(core_re_files documented_unit_gates direct_thread_pairs
            thread_only_tests)) {
        die "Strict regex ledger has no $key array\n"
            unless ref($ledger->{$key}) eq 'ARRAY';
    }
    my %selected = map { $_ => 1 } (
        @{$ledger->{core_re_files}},
        @{$ledger->{documented_unit_gates}},
        @{$ledger->{thread_only_tests}},
    );
    for my $pair (@{$ledger->{direct_thread_pairs}}) {
        die "Strict regex ledger has a malformed direct/thread pair\n"
            unless ref($pair) eq 'HASH' && $pair->{direct} && $pair->{thread};
        $selected{$pair->{direct}} = 1;
        $selected{$pair->{thread}} = 1;
    }
    for my $file (keys %selected) {
        die "Strict regex ledger references missing test path: $file\n" unless -f $file;
    }
    return sort keys %selected;
}

sub write_file_list {
    my ($file, $files) = @_;
    open my $fh, '>:raw', $file or die "Cannot write runner list $file: $!\n";
    print {$fh} "$_\n" for @$files;
    close $fh or die "Cannot close runner list $file: $!\n";
}

sub comparison_identity {
    my ($files) = @_;
    my @canonical = sort @$files;
    return {
        compared_files => \@canonical,
        compared_files_sha256 => sha256_hex(
            join('', map { "$_\n" } @canonical)),
    };
}

sub retain_raw_tap_evidence {
    my ($artifact_root, $expected_files, %backend_result) = @_;
    my $raw_root = File::Spec->catdir($artifact_root, 'raw-tap');
    die "Refusing pre-existing raw TAP directory $raw_root\n" if -e $raw_root;
    make_path($raw_root, { mode => 0700 });
    my (@entry, %seen_binding, %seen_retained_path);
    my $aggregate = 0;
    for my $backend (sort keys %backend_result) {
        die "Raw TAP backend is unsupported: $backend\n"
            unless $backend =~ /\A(?:jvm|interpreter)\z/;
        my $document = load_json($backend_result{$backend},
            "$backend runner results");
        die "$backend runner results have no result map\n"
            unless ref($document->{results}) eq 'HASH';
        my @actual_files = sort keys %{$document->{results}};
        my @selected_files = sort @$expected_files;
        die "$backend runner result map differs from the selected file set\n"
            unless JSON::PP->new->canonical->encode(\@actual_files)
                eq JSON::PP->new->canonical->encode(\@selected_files);
        for my $file (sort keys %{$document->{results}}) {
            die "Unsafe or noncanonical runner file identity: $file\n"
                unless safe_relative_file($file);
            die "Duplicate raw TAP row binding: $backend/$file\n"
                if $seen_binding{"$backend\0$file"}++;
            my $row = $document->{results}{$file};
            die "$backend runner row is malformed: $file\n"
                unless ref($row) eq 'HASH' && ($row->{file} // '') eq $file;
            my $source = $row->{raw_output_path};
            die "$backend raw TAP path is missing or contains control bytes: $file\n"
                unless defined($source) && !ref($source) && length($source)
                    && $source !~ /[\x00-\x1f\x7f]/;
            my @source_stat = lstat($source);
            die "$backend raw TAP final entry is missing or symlinked: $file\n"
                unless @source_stat && -f _ && !-l _;
            die "$backend raw TAP exceeds per-file bound: $file\n"
                if $source_stat[7] > $MAX_RAW_TAP_BYTES;

            my $relative = join('/', 'raw-tap', $backend,
                sha256_hex("$backend\0$file") . '.tap');
            die "Raw TAP retained-path collision: $relative\n"
                if $seen_retained_path{$relative}++;
            my $destination = File::Spec->catfile($artifact_root,
                split m{/}, $relative);
            make_path(dirname($destination), { mode => 0700 });
            my ($size, $sha) = copy_raw_tap(
                $source, $destination, "$backend raw TAP $file");
            $aggregate += $size;
            die "Raw TAP evidence exceeds aggregate byte bound\n"
                if $aggregate > $MAX_RAW_TAP_AGGREGATE_BYTES;
            push @entry, {
                backend => $backend, file => $file, path => $relative,
                size => 0 + $size, sha256 => $sha,
            };
            $row->{raw_output_path} = $relative;
        }
        write_json($backend_result{$backend}, $document);
    }
    die "Raw TAP evidence exceeds file-count bound\n"
        if @entry > $MAX_RAW_TAP_FILES;
    return {
        schema_version => 1,
        kind => 'regex-raw-tap-index',
        mapping => 'sha256-backend-nul-normalized-relative-file/v1',
        aggregate_bytes => 0 + $aggregate,
        entries => \@entry,
    };
}

sub copy_raw_tap {
    my ($source, $destination, $label) = @_;
    my @source_stat = lstat($source);
    die "$label is missing, nonregular, or symlinked\n"
        unless @source_stat && -f _ && !-l _;
    open my $input, '<:raw', $source
        or die "Cannot open $label: $!\n";
    open my $output, '>:raw', $destination
        or die "Cannot create retained $label: $!\n";
    my $digest = Digest::SHA->new(256);
    my $size = 0;
    while (1) {
        my $read = read($input, my $buffer, 64 * 1024);
        die "Cannot read $label: $!\n" unless defined $read;
        last unless $read;
        $size += $read;
        die "$label exceeds per-file bound\n" if $size > $MAX_RAW_TAP_BYTES;
        $digest->add($buffer);
        print {$output} $buffer or die "Cannot write retained $label: $!\n";
    }
    close $input or die "Cannot close $label: $!\n";
    close $output or die "Cannot close retained $label: $!\n";
    my @retained = lstat($destination);
    die "Retained $label is not a regular nonsymlink file\n"
        unless @retained && -f _ && !-l _;
    my $sha = $digest->hexdigest;
    die "Retained $label size or digest differs after copy\n"
        unless $retained[7] == $size && sha256_file($destination) eq $sha;
    return ($size, $sha);
}

sub revalidate_raw_tap_evidence {
    my ($artifact_root, $index, %backend_result) = @_;
    die "Raw TAP index is malformed\n"
        unless ref($index) eq 'HASH' && ref($index->{entries}) eq 'ARRAY';
    my (%expected_path, %identity, %entry_for);
    my $aggregate = 0;
    for my $entry (@{$index->{entries}}) {
        assert_exact_keys($entry, 'raw TAP index entry',
            qw(backend file path sha256 size));
        die "Raw TAP index entry is malformed\n"
            unless ($entry->{backend} // '') =~ /\A(?:jvm|interpreter)\z/
                && safe_relative_file($entry->{file})
                && safe_relative_file($entry->{path})
                && ($entry->{sha256} // '') =~ /\A[0-9a-f]{64}\z/
                && defined($entry->{size})
                && "$entry->{size}" =~ /\A(?:0|[1-9][0-9]*)\z/;
        die "Duplicate raw TAP index identity\n"
            if $identity{"$entry->{backend}\0$entry->{file}"}++;
        $entry_for{"$entry->{backend}\0$entry->{file}"} = $entry;
        die "Duplicate raw TAP retained path\n" if $expected_path{$entry->{path}}++;
        my $expected_relative = join('/', 'raw-tap', $entry->{backend},
            sha256_hex("$entry->{backend}\0$entry->{file}") . '.tap');
        die "Raw TAP retained path is not deterministic\n"
            unless $entry->{path} eq $expected_relative;
        my $path = File::Spec->catfile($artifact_root, split m{/}, $entry->{path});
        my @stat = lstat($path);
        die "Retained raw TAP changed after validation: $entry->{path}\n"
            unless @stat && -f _ && !-l _
                && $stat[7] == $entry->{size}
                && sha256_file($path) eq $entry->{sha256};
        $aggregate += $stat[7];
    }
    die "Raw TAP aggregate identity changed\n"
        unless $aggregate == ($index->{aggregate_bytes} // -1);
    for my $backend (sort keys %backend_result) {
        my $document = load_json($backend_result{$backend},
            "$backend retained runner results");
        die "$backend retained runner results have no result map\n"
            unless ref($document->{results}) eq 'HASH';
        my @index_files = sort map { $_->{file} }
            grep { $_->{backend} eq $backend } @{$index->{entries}};
        my @result_files = sort keys %{$document->{results}};
        die "$backend retained runner result/index inventory differs\n"
            unless JSON::PP->new->canonical->encode(\@index_files)
                eq JSON::PP->new->canonical->encode(\@result_files);
        for my $file (@result_files) {
            my $entry = $entry_for{"$backend\0$file"};
            my $row = $document->{results}{$file};
            die "$backend retained runner row/index binding differs: $file\n"
                unless ref($row) eq 'HASH' && ($row->{file} // '') eq $file
                    && ($row->{raw_output_path} // '') eq $entry->{path};
        }
    }
}

sub safe_relative_file {
    my ($file) = @_;
    return 0 unless defined($file) && !ref($file) && length($file);
    return 0 if $file =~ /[\\\x00-\x1f\x7f]/
        || File::Spec->file_name_is_absolute($file)
        || $file =~ /\A[A-Za-z]:/ || $file =~ m{//};
    my @parts = split m{/}, $file, -1;
    return 0 if grep { $_ eq '' || $_ eq '.' || $_ eq '..' } @parts;
    return join('/', @parts) eq $file;
}

sub read_raw {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "Cannot read $file: $!\n";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $file: $!\n";
    return $contents;
}

sub run_logged {
    my (%arg) = @_;
    my @command = @{$arg{command}};
    my $status = run_to_log($arg{log}, $arg{environment}, $arg{timeout}, @command);
    push @{$arg{commands}}, { name => $arg{name}, argv => \@command,
        environment => $arg{environment} // {} };
    $arg{statuses}{$arg{name}} = $status;
    if ($status == 124 && defined $arg{timeout}) {
        warn "$arg{name} timed out after $arg{timeout}s; see $arg{log}\n";
        exit 124;
    }
    die "$arg{name} failed with exit status $status; see $arg{log}\n" if $status != 0;
}

sub run_to_log {
    my ($log, $environment, $timeout, @command) = @_;
    my $pid = fork();
    die "Cannot fork $command[0]: $!\n" unless defined $pid;
    if ($pid == 0) {
        open STDOUT, '>:raw', $log or die "Cannot write $log: $!\n";
        open STDERR, '>&', \*STDOUT or die "Cannot redirect stderr: $!\n";
        if ($environment) {
            for my $key (keys %$environment) {
                defined $environment->{$key} ? ($ENV{$key} = $environment->{$key}) : delete $ENV{$key};
            }
        }
        exec { $command[0] } @command;
        die "Cannot execute $command[0]: $!\n";
    }
    if (defined $timeout) {
        my $completed = eval {
            local $SIG{ALRM} = sub { die "acceptance child timeout\n" };
            alarm $timeout;
            waitpid($pid, 0);
            alarm 0;
            1;
        };
        if (!$completed) {
            alarm 0;
            kill 'TERM', $pid;
            select undef, undef, undef, 0.1;
            kill 'KILL', $pid if kill 0, $pid;
            waitpid($pid, 0);
            return 124;
        }
    } else {
        waitpid($pid, 0);
    }
    return $? >> 8 if $? != -1 && ($? & 127) == 0;
    return 255;
}

sub capture_command {
    my ($command) = @_;
    open my $fh, '-|', @$command or die "Cannot execute $command->[0]: $!\n";
    my $output = do { local $/; <$fh> };
    close $fh or die "Command $command->[0] failed with status $?\n";
    return $output;
}

sub load_json {
    my ($file, $label) = @_;
    open my $fh, '<:raw', $file or die "Cannot read $label $file: $!\n";
    my $document = eval { JSON::PP->new->utf8->decode(do { local $/; <$fh> }) };
    close $fh;
    die "Invalid $label JSON in $file\n" unless $document && ref $document eq 'HASH';
    return $document;
}

sub verify_comparison {
    my ($file, $expected_files, $allow_inherited_invalid, $expected_identity,
        $log) = @_;
    my $comparison = load_json($file, 'comparison');
    die "Comparison $file has no summary\n" unless ref $comparison->{summary} eq 'HASH';
    my $count = $comparison->{summary}{candidate_files};
    die "Comparison $file has file count drift\n"
        unless defined $count && $count == $expected_files;
    my @expected = sort @$expected_identity;
    my $digest = sha256_hex(join('', map { "$_\n" } @expected));
    die "Comparison $file has missing or stale compared-file identity\n"
        unless ref($comparison->{compared_files}) eq 'ARRAY'
            && JSON::PP->new->canonical->encode($comparison->{compared_files})
                eq JSON::PP->new->canonical->encode(\@expected)
            && ($comparison->{compared_files_sha256} // '') eq $digest;
    my $log_bytes = read_raw($log);
    die "Comparison log $log does not bind the compared-file digest\n"
        unless index($log_bytes, $digest) >= 0;
    die "Comparison log $log does not bind compared file $_\n"
        for grep { index($log_bytes, $_) < 0 } @expected;
    for my $key (qw(regressions missing_files new_invalid)) {
        die "Comparison $file has non-empty $key\n"
            unless ref($comparison->{$key}) eq 'ARRAY' && !@{$comparison->{$key}};
    }
    for my $key (qw(added_files execution_issues zero_tap truncated inherited_invalid)) {
        die "Comparison $file has malformed $key\n"
            unless ref($comparison->{$key}) eq 'ARRAY';
    }
    return if $allow_inherited_invalid;
    for my $key (qw(execution_issues zero_tap truncated)) {
        die "Comparison $file has non-empty $key\n"
            unless ref($comparison->{$key}) eq 'ARRAY' && !@{$comparison->{$key}};
    }
}

sub sha256_file {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "Cannot hash $file: $!\n";
    my $sha = Digest::SHA->new(256);
    $sha->addfile($fh);
    close $fh;
    return $sha->hexdigest;
}

sub abs_file {
    my ($file) = @_;
    return abs_path($file) // File::Spec->rel2abs($file);
}

sub write_json {
    my ($file, $document) = @_;
    open my $fh, '>:raw', $file or die "Cannot write $file: $!\n";
    print {$fh} JSON::PP->new->canonical->pretty->encode($document);
    close $fh or die "Cannot close $file: $!\n";
}
