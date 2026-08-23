#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA;
use Fcntl qw(O_CREAT O_EXCL O_RDONLY O_WRONLY);
use File::Basename qw(dirname basename);
use File::Spec;
use Getopt::Long qw(GetOptions Configure);
use IO::Handle;
use JSON::PP;

use constant {
    MAX_CORPUS_MANIFEST_BYTES => 4 * 1024 * 1024,
    MAX_AUTHORITY_JSON_BYTES => 16 * 1024 * 1024,
    MAX_BASELINE_BYTES => 512 * 1024 * 1024,
    MAX_LAUNCHER_BYTES => 16 * 1024 * 1024,
    MAX_JAR_BYTES => 2 * 1024 * 1024 * 1024,
    MAX_SBOM_BYTES => 256 * 1024 * 1024,
    MAX_CORPUS_ARTIFACT_BYTES => 1024 * 1024 * 1024,
    MAX_CORPUS_TOTAL_BYTES => 8 * 1024 * 1024 * 1024,
    MAX_JSON_DEPTH => 64,
};

validate_cli_tokens(\@ARGV);
Configure(qw(no_auto_abbrev no_ignore_case require_order));
my (%option, $help);
GetOptions(
    'source-dir=s' => \$option{source},
    'perl5-dir=s' => \$option{perl5},
    'jperl=s' => \$option{jperl},
    'jcpan=s' => \$option{jcpan},
    'jar=s' => \$option{jar},
    'sbom=s' => \$option{sbom},
    'baseline=s' => \$option{baseline},
    'corpus-manifest=s' => \$option{corpus_manifest},
    'requirements=s' => \$option{requirements},
    'cpan-policy=s' => \$option{cpan_policy},
    'package-evidence=s' => \$option{package_evidence},
    'make-evidence=s' => \$option{make_evidence},
    'git=s' => \$option{git},
    'output=s' => \$option{output},
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;
for my $required (qw(source perl5 jperl jcpan jar sbom baseline corpus_manifest output)) {
    my $display = $required;
    $display =~ tr/_/-/;
    die "--$display is required\n"
        unless defined($option{$required}) && length($option{$required});
}
my @authority_options = qw(requirements cpan_policy package_evidence make_evidence git);
my @selected_authority = grep { defined($option{$_}) && length($option{$_}) }
    @authority_options;
die "Strict authority options must be supplied together: --requirements, --cpan-policy, --package-evidence, --make-evidence, --git\n"
    if @selected_authority && @selected_authority != @authority_options;
my $strict_authority = @selected_authority ? 1 : 0;
if (!$strict_authority) {
    $option{git} = locate_legacy_git();
}

my %directory;
for my $name (qw(source perl5)) {
    $directory{$name} = canonical_directory($option{$name}, "$name checkout");
}
my %limit = (
    jperl => MAX_LAUNCHER_BYTES,
    jcpan => MAX_LAUNCHER_BYTES,
    jar => MAX_JAR_BYTES,
    sbom => MAX_SBOM_BYTES,
    baseline => MAX_BASELINE_BYTES,
    corpus_manifest => MAX_CORPUS_MANIFEST_BYTES,
    requirements => MAX_AUTHORITY_JSON_BYTES,
    cpan_policy => MAX_AUTHORITY_JSON_BYTES,
    package_evidence => MAX_AUTHORITY_JSON_BYTES,
    make_evidence => MAX_AUTHORITY_JSON_BYTES,
    git => MAX_LAUNCHER_BYTES,
);
my %file;
for my $name (qw(jperl jcpan jar sbom baseline corpus_manifest git),
        ($strict_authority ? qw(requirements cpan_policy package_evidence
            make_evidence) : ())) {
    $file{$name} = canonical_file($option{$name}, $name, $limit{$name});
}
die "jperl is not executable\n" unless -x $file{jperl}{path};
die "jcpan is not executable\n" unless -x $file{jcpan}{path};
die "git is not executable\n" unless -x $file{git}{path};

my $expected_jperl = File::Spec->catfile($directory{source}, 'jperl');
my $expected_jcpan = File::Spec->catfile($directory{source}, 'jcpan');
die "jperl must be the tracked launcher in the selected source checkout\n"
    unless $file{jperl}{path} eq $expected_jperl;
die "jcpan must be the tracked launcher in the selected source checkout\n"
    unless $file{jcpan}{path} eq $expected_jcpan;
verify_tracked_file($directory{source}, 'jperl', $file{git}{path});
verify_tracked_file($directory{source}, 'jcpan', $file{git}{path});

my $source_commit = clean_checkout_commit($directory{source}, 'source', $file{git}{path});
my $perl5_commit = clean_checkout_commit($directory{perl5}, 'perl5', $file{git}{path});
my ($requirements, $cpan_policy, $package, $make);
my ($package_artifacts, $make_artifacts) = ([], []);
if ($strict_authority) {
    $requirements = load_strict_json($file{requirements}, 'acceptance requirements');
    $cpan_policy = load_strict_json($file{cpan_policy}, 'CPAN policy');
    validate_requirements_policy($requirements, $cpan_policy,
        $file{cpan_policy}{sha256}, $file{baseline}{sha256});
    $package = load_strict_json($file{package_evidence}, 'package evidence');
    $package_artifacts = validate_package_evidence($package, \%directory,
        \%file, $source_commit);
    $make = load_strict_json($file{make_evidence}, 'make evidence');
    $make_artifacts = validate_make_evidence($make, \%directory, \%file,
        $source_commit);
}
my $corpus_bytes = read_snapshot($file{corpus_manifest}, 'corpus manifest');
reject_duplicate_json_keys($corpus_bytes, 'corpus manifest');
my $corpus = eval { JSON::PP->new->utf8->decode($corpus_bytes) };
die "Invalid corpus manifest JSON\n" unless ref($corpus) eq 'HASH' && !$@;
my $corpus_artifacts = validate_corpus_manifest($corpus, \%directory, \%file,
    $source_commit, $perl5_commit);
my @authority_artifacts = (@$corpus_artifacts, @$package_artifacts,
    @$make_artifacts);

my $document = {
    schema_version => 1,
    mode => 'acceptance',
    identity => {
        source_commit => $source_commit,
        runner_commit => $source_commit,
        perl5_commit => $perl5_commit,
        jperl_sha256 => $file{jperl}{sha256},
        jar_sha256 => $file{jar}{sha256},
        sbom_sha256 => $file{sbom}{sha256},
    },
    inputs => {
        source => { path => $directory{source}, commit => $source_commit },
        perl5 => { path => $directory{perl5}, commit => $perl5_commit },
        jperl => { path => $file{jperl}{path}, sha256 => $file{jperl}{sha256} },
        jcpan => { path => $file{jcpan}{path}, sha256 => $file{jcpan}{sha256} },
        jar => { path => $file{jar}{path}, sha256 => $file{jar}{sha256} },
        sbom => { path => $file{sbom}{path}, sha256 => $file{sbom}{sha256} },
    },
};
assert_exact_keys($document, 'CPAN launch manifest',
    qw(schema_version mode identity inputs));
assert_exact_keys($document->{identity}, 'CPAN launch identity',
    qw(source_commit runner_commit perl5_commit jperl_sha256 jar_sha256 sbom_sha256));
assert_exact_keys($document->{inputs}, 'CPAN launch inputs',
    qw(source perl5 jperl jcpan jar sbom));

my $output_parent = canonical_directory(dirname($option{output}), 'output parent');
my $output = File::Spec->catfile($output_parent, basename($option{output}));
die "--output must be a canonical absolute path\n" unless $option{output} eq $output;
my %published_path = (
    launch => $output,
    bridge => "$output.bridge.json",
    seal => "$output.bridge.sha256",
    authority => "$output.authority.json",
);
my @publication_names = $strict_authority
    ? qw(launch bridge seal authority) : qw(launch);
for my $name (@publication_names) {
    my $path = $published_path{$name};
    die "Refusing to overwrite launch manifest: $path\n"
        if $name eq 'launch' && (-e $path || -l $path);
    die "Refusing to overwrite launch-manifest bundle member: $path\n"
        if -e $path || -l $path;
}
verify_all_inputs(\%directory, \%file, \@authority_artifacts,
    $source_commit, $perl5_commit);
my $launch_bytes = JSON::PP->new->utf8->canonical->pretty->encode($document);
if (!$strict_authority) {
    publish_legacy($published_path{launch}, $launch_bytes, sub {
        verify_all_inputs(\%directory, \%file, \@authority_artifacts,
            $source_commit, $perl5_commit);
    });
    print "$output\n";
    exit 0;
}
my $bridge = authoritative_bridge(\%directory, \%file, $source_commit,
    $perl5_commit, $launch_bytes, $package, $make);
my $bridge_bytes = JSON::PP->new->utf8->canonical->pretty->encode($bridge);
my $seal_bytes = Digest::SHA::sha256_hex($bridge_bytes)
    . "  " . basename($published_path{bridge}) . "\n";
my $authority = authority_marker(\%published_path, $launch_bytes,
    $bridge_bytes, $seal_bytes, $bridge->{tuple_sha256});
my $authority_bytes = JSON::PP->new->utf8->canonical->pretty->encode($authority);
publish_bundle(\%published_path, {
    launch => $launch_bytes, bridge => $bridge_bytes, seal => $seal_bytes,
    authority => $authority_bytes,
}, sub {
    verify_all_inputs(\%directory, \%file, \@authority_artifacts,
        $source_commit, $perl5_commit);
});
print "$output\n";

sub validate_requirements_policy {
    my ($requirements, $policy, $policy_sha, $baseline_sha) = @_;
    assert_exact_keys($requirements, 'acceptance requirements', qw(schema_version
        policy baseline_sha256 performance_acceptance cpan_acceptance
        allowed_cpan_excluded_audit_classifications required_ci_platforms
        required_gates));
    die "Acceptance requirements schema_version must be 1\n"
        unless ($requirements->{schema_version} // 0) == 1;
    die "Acceptance requirements baseline hash is malformed\n"
        unless ($requirements->{baseline_sha256} // '') =~ /\A[0-9a-f]{64}\z/;
    die "Acceptance requirements baseline differs from selected baseline\n"
        unless $requirements->{baseline_sha256} eq $baseline_sha;
    my $cpan = $requirements->{cpan_acceptance};
    assert_exact_keys($cpan, 'requirements CPAN policy', qw(policy_sha256
        expected_targets required_modes));
    die "Requirements CPAN policy hash differs from selected policy\n"
        unless ($cpan->{policy_sha256} // '') eq $policy_sha;
    assert_exact_keys($policy, 'CPAN policy', qw(schema_version expected_targets targets));
    die "CPAN policy schema_version must be 1\n"
        unless ($policy->{schema_version} // 0) == 1;
    die "CPAN policy target lists are malformed\n"
        unless ref($policy->{expected_targets}) eq 'ARRAY'
            && @{$policy->{expected_targets}}
            && ref($policy->{targets}) eq 'ARRAY';
    my (%expected, %actual);
    for my $target (@{$policy->{expected_targets}}) {
        die "CPAN policy expected target is invalid or duplicated\n"
            unless defined($target) && !ref($target) && length($target)
                && !$expected{$target}++;
    }
    for my $record (@{$policy->{targets}}) {
        assert_exact_keys($record, 'CPAN target policy', qw(name rationale
            timeout_seconds required_modes focused_selector_permitted
            approved_warning_patterns));
        my $name = $record->{name} // '';
        die "CPAN policy target is invalid or duplicated\n"
            unless length($name) && !$actual{$name}++;
        die "CPAN target policy fields are malformed: $name\n"
            unless length($record->{rationale} // '')
                && ($record->{timeout_seconds} // '') =~ /\A[1-9][0-9]*\z/
                && ref($record->{required_modes}) eq 'ARRAY'
                && ref($record->{approved_warning_patterns}) eq 'ARRAY'
                && JSON::PP::is_bool($record->{focused_selector_permitted});
        die "CPAN target mode set differs from requirements: $name\n"
            unless canonical([sort @{$record->{required_modes}}])
                eq canonical([sort @{$cpan->{required_modes} // []}]);
    }
    die "CPAN policy target record set differs from expected targets\n"
        unless canonical([sort keys %expected]) eq canonical([sort keys %actual]);
    die "Requirements target set differs from selected CPAN policy\n"
        unless canonical([sort @{$cpan->{expected_targets} // []}])
            eq canonical([sort keys %expected]);
}

sub validate_package_evidence {
    my ($package, $directory, $file, $source_commit) = @_;
    assert_exact_keys($package, 'package evidence', qw(schema_version kind producer
        verified identity completion artifacts missing_entries duplicate_entries));
    die "Package evidence is not a strict accepted record\n"
        unless ($package->{schema_version} // 0) == 1
            && ($package->{kind} // '') eq 'packaging'
            && JSON::PP::is_bool($package->{verified}) && $package->{verified}
            && ($package->{missing_entries} // -1) == 0
            && ($package->{duplicate_entries} // -1) == 0;
    assert_exact_keys($package->{identity}, 'package identity',
        qw(source_commit jar_sha256 sbom_sha256));
    die "Package source/JAR/SBOM identity differs from selected tuple\n"
        unless $package->{identity}{source_commit} eq $source_commit
            && $package->{identity}{jar_sha256} eq $file->{jar}{sha256}
            && $package->{identity}{sbom_sha256} eq $file->{sbom}{sha256};
    assert_clean_completion($package->{completion}, 'package completion', 0);
    my $artifacts = $package->{artifacts};
    assert_exact_keys($artifacts, 'package artifacts', qw(report deliverables
        sbom_inputs logs notice_license));
    assert_exact_keys($artifacts->{deliverables}, 'package deliverables',
        qw(jar sbom deb));
    assert_exact_keys($artifacts->{sbom_inputs}, 'package SBOM inputs',
        qw(java_bom perl_bom));
    die "Package logs must be a nonempty object\n"
        unless ref($artifacts->{logs}) eq 'HASH' && keys %{$artifacts->{logs}};
    my $base = dirname($file->{package_evidence}{path});
    my (@records, %record);
    for my $pair (
        [report => $artifacts->{report}],
        [jar => $artifacts->{deliverables}{jar}],
        [sbom => $artifacts->{deliverables}{sbom}],
        [deb => $artifacts->{deliverables}{deb}],
        [java_bom => $artifacts->{sbom_inputs}{java_bom}],
        [perl_bom => $artifacts->{sbom_inputs}{perl_bom}],
        [notice_license => $artifacts->{notice_license}],
        (map { ["log:$_" => $artifacts->{logs}{$_}] }
            sort keys %{$artifacts->{logs}}),
    ) {
        my ($name, $descriptor) = @$pair;
        $record{$name} = resolve_retained_descriptor($descriptor, $base,
            "package $name artifact");
        push @records, $record{$name};
    }
    die "Retained package JAR/SBOM differs from selected bytes\n"
        unless $record{jar}{sha256} eq $file->{jar}{sha256}
            && $record{sbom}{sha256} eq $file->{sbom}{sha256};
    my $report = load_strict_json($record{report}, 'retained package report');
    assert_exact_keys($report, 'retained package report', qw(schema_version kind
        producer mode authoritative status verified missing_entries duplicate_entries
        jar_sha256 sbom_sha256 identity build_contract tools verifiers configs
        immutable_inputs package commands artifacts sbom_relation trees notice_license
        notice_license_artifact retained_artifacts));
    die "Retained package report is not the accepted strict relationship record\n"
        unless ($report->{schema_version} // 0) == 1
            && ($report->{kind} // '') eq 'phase36-package-evidence-report'
            && ($report->{mode} // '') eq 'acceptance'
            && ($report->{status} // '') eq 'pass'
            && JSON::PP::is_bool($report->{verified}) && $report->{verified}
            && JSON::PP::is_bool($report->{authoritative}) && !$report->{authoritative}
            && ($report->{missing_entries} // -1) == 0
            && ($report->{duplicate_entries} // -1) == 0;
    assert_exact_keys($report->{identity}, 'retained package report identity',
        qw(source_root source_commit jar_sha256 sbom_sha256));
    die "Retained package report identity differs from selected tuple\n"
        unless $report->{identity}{source_root} eq $directory->{source}
            && $report->{identity}{source_commit} eq $source_commit
            && $report->{identity}{jar_sha256} eq $file->{jar}{sha256}
            && $report->{identity}{sbom_sha256} eq $file->{sbom}{sha256}
            && ($report->{jar_sha256} // '') eq $file->{jar}{sha256}
            && ($report->{sbom_sha256} // '') eq $file->{sbom}{sha256};
    my $relation = $report->{sbom_relation};
    assert_exact_keys($relation, 'package SBOM relation', qw(java_bom_sha256
        perl_bom_sha256 sbom_sha256 relation verified));
    die "Package JAR/SBOM relationship is not strict or does not match retained bytes\n"
        unless ($relation->{relation} // '') eq
                'java-components+joni-fork+perl-components'
            && JSON::PP::is_bool($relation->{verified}) && $relation->{verified}
            && $relation->{java_bom_sha256} eq $record{java_bom}{sha256}
            && $relation->{perl_bom_sha256} eq $record{perl_bom}{sha256}
            && $relation->{sbom_sha256} eq $record{sbom}{sha256};
    return \@records;
}

sub validate_make_evidence {
    my ($make, $directory, $file, $source_commit) = @_;
    assert_exact_keys($make, 'make evidence', qw(artifacts authoritative command
        completion failure_scan identity inputs kind mode producer schema
        schema_version seal source status tools verified warning_scan));
    die "Make evidence is not a strict authoritative pass\n"
        unless ($make->{schema} // '') eq 'perlonjava.phase36.make-evidence/v1'
            && ($make->{schema_version} // 0) == 1
            && ($make->{kind} // '') eq 'make'
            && ($make->{mode} // '') eq 'acceptance'
            && ($make->{status} // '') eq 'pass'
            && JSON::PP::is_bool($make->{verified}) && $make->{verified}
            && JSON::PP::is_bool($make->{authoritative}) && $make->{authoritative};
    assert_exact_keys($make->{identity}, 'make identity', qw(jar_embedded_commit
        jar_reported_commit jar_sha256 runner_commit source_commit));
    die "Make source/JAR identity differs from selected tuple\n"
        unless $make->{identity}{source_commit} eq $source_commit
            && $make->{identity}{runner_commit} eq $source_commit
            && $make->{identity}{jar_reported_commit} eq $source_commit
            && $make->{identity}{jar_embedded_commit} eq $source_commit
            && $make->{identity}{jar_sha256} eq $file->{jar}{sha256};
    assert_clean_completion($make->{completion}, 'make completion', 1);
    for my $scan (qw(warning_scan failure_scan)) {
        assert_exact_keys($make->{$scan}, "make $scan", qw(classifier
            classifier_sha256 complete_log_sha256 count matches));
        die "Make $scan is not clean\n"
            unless ($make->{$scan}{count} // -1) == 0
                && ref($make->{$scan}{matches}) eq 'ARRAY'
                && !@{$make->{$scan}{matches}};
    }
    assert_exact_keys($make->{source}, 'make source', qw(root before after));
    die "Make source root differs from selected checkout\n"
        unless ($make->{source}{root} // '') eq $directory->{source};
    for my $when (qw(before after)) {
        my $state = $make->{source}{$when};
        assert_exact_keys($state, "make source $when", qw(all_status_sha256
            diff_sha256 extras head status_sha256 tracked_clean));
        die "Make source $when identity is not clean at selected commit\n"
            unless ($state->{head} // '') eq $source_commit
                && JSON::PP::is_bool($state->{tracked_clean})
                && $state->{tracked_clean};
        for my $hash (qw(all_status_sha256 diff_sha256 status_sha256)) {
            die "Make source $when $hash is malformed\n"
                unless ($state->{$hash} // '') =~ /\A[0-9a-f]{64}\z/;
        }
        assert_exact_keys($state->{extras}, "make source $when extras",
            qw(authority_inputs generated_file_count generated_paths
                generated_total_bytes));
    }
    assert_exact_keys($make->{command}, 'make command', qw(argv cwd
        duration_milliseconds environment finished_utc started_utc));
    die "Make command is not rooted in selected source checkout\n"
        unless ($make->{command}{cwd} // '') eq $directory->{source}
            && ref($make->{command}{argv}) eq 'ARRAY'
            && @{$make->{command}{argv}}
            && ref($make->{command}{environment}) eq 'HASH';
    assert_exact_keys($make->{tools}, 'make tools', qw(git jar_tool java make
        perl producer shell));
    assert_exact_keys($make->{inputs}, 'make inputs', qw(build_gradle
        gradle_wrapper_jar gradle_wrapper_properties gradlew makefile
        settings_gradle));
    assert_exact_keys($make->{artifacts}, 'make artifacts', qw(jar jar_embedded
        jar_version make_log source_after source_before tool_versions));
    my (@records, %record);
    for my $name (sort keys %{$make->{artifacts}}) {
        $record{$name} = resolve_absolute_descriptor($make->{artifacts}{$name},
            "make $name artifact");
        push @records, $record{$name};
    }
    for my $name (qw(git jar_tool java make perl shell)) {
        my $descriptor = $make->{tools}{$name};
        assert_exact_keys($descriptor, "make tool $name", qw(path sha256 size
            version_sha256));
        my $record = resolve_absolute_descriptor({ map {
            $_ => $descriptor->{$_} } qw(path sha256 size) },
            "make tool $name");
        push @records, $record;
        die "Make tool $name version identity is malformed\n"
            unless ($descriptor->{version_sha256} // '') =~ /\A[0-9a-f]{64}\z/;
        die "Make tool $name is no longer executable\n" unless -x $record->{path};
        die "Make trusted Git differs from bridge Git authority\n"
            if $name eq 'git' && ($record->{path} ne $file->{git}{path}
                || $record->{sha256} ne $file->{git}{sha256});
    }
    my $producer = resolve_absolute_descriptor($make->{tools}{producer},
        'make producer');
    push @records, $producer;
    for my $name (sort keys %{$make->{inputs}}) {
        my $record = resolve_absolute_descriptor($make->{inputs}{$name},
            "make input $name");
        push @records, $record;
    }
    die "Make command argv differs from its trusted make executable\n"
        unless @{$make->{command}{argv}} == 1
            && $make->{command}{argv}[0] eq $make->{tools}{make}{path};
    die "Make JAR artifact differs from selected JAR\n"
        unless $record{jar}{path} eq $file->{jar}{path}
            && $record{jar}{sha256} eq $file->{jar}{sha256};
    for my $scan (qw(warning_scan failure_scan)) {
        die "Make $scan does not bind the retained complete log\n"
            unless ($make->{$scan}{complete_log_sha256} // '')
                eq $record{make_log}{sha256}
                && ($make->{$scan}{classifier_sha256} // '')
                    =~ /\A[0-9a-f]{64}\z/;
    }
    my $embedded = load_strict_json($record{jar_embedded},
        'embedded JAR authentication');
    assert_exact_keys($embedded, 'embedded JAR authentication', qw(method argv
        archive_tool capture_sha256 capture_size jar_sha256 resolved_commit));
    die "Embedded JAR authentication does not bind selected JAR and commit\n"
        unless ($embedded->{resolved_commit} // '') eq $source_commit
            && ($embedded->{jar_sha256} // '') eq $file->{jar}{sha256}
            && ($embedded->{method} // '') =~ /\A(?:trusted-unzip-configuration-class|bounded-direct-content-scan)\z/;
    die "Embedded JAR capture identity is malformed\n"
        unless ($embedded->{capture_sha256} // '') =~ /\A[0-9a-f]{64}\z/
            && ($embedded->{capture_size} // '') =~ /\A[0-9]+\z/
            && ref($embedded->{argv}) eq 'ARRAY';
    my $archive_tool = resolve_absolute_descriptor($embedded->{archive_tool},
        'embedded JAR archive tool');
    push @records, $archive_tool;
    assert_exact_keys($make->{seal}, 'make seal', qw(algorithm payload_sha256));
    my %payload = %$make;
    delete $payload{seal};
    die "Make canonical payload seal is invalid\n"
        unless ($make->{seal}{algorithm} // '') eq 'SHA-256'
            && ($make->{seal}{payload_sha256} // '') eq
                Digest::SHA::sha256_hex(canonical(\%payload));
    my $external_seal = canonical_file($file->{make_evidence}{path} . '.seal',
        'make external seal', 512);
    my $seal_text = read_snapshot($external_seal, 'make external seal');
    die "Make external seal is invalid\n"
        unless $seal_text eq 'SHA-256 ' . $make->{seal}{payload_sha256} . ' '
            . $file->{make_evidence}{sha256} . "\n";
    push @records, $external_seal;
    return \@records;
}

sub assert_clean_completion {
    my ($completion, $label, $with_truncated) = @_;
    my @keys = qw(exit_code signal timeout incomplete review_stop);
    push @keys, 'truncated' if $with_truncated;
    assert_exact_keys($completion, $label, @keys);
    die "$label is not a clean completion tuple\n"
        unless ($completion->{exit_code} // -1) == 0
            && ($completion->{signal} // -1) == 0
            && JSON::PP::is_bool($completion->{timeout}) && !$completion->{timeout}
            && JSON::PP::is_bool($completion->{incomplete}) && !$completion->{incomplete}
            && JSON::PP::is_bool($completion->{review_stop}) && !$completion->{review_stop}
            && (!$with_truncated || (JSON::PP::is_bool($completion->{truncated})
                && !$completion->{truncated}));
}

sub resolve_retained_descriptor {
    my ($descriptor, $base, $label) = @_;
    assert_exact_keys($descriptor, $label, qw(path sha256 size));
    my $relative = $descriptor->{path} // '';
    die "$label path is unsafe\n"
        if !length($relative) || File::Spec->file_name_is_absolute($relative)
            || grep { $_ eq '..' || $_ eq '.' || !length($_) }
                File::Spec->splitdir($relative);
    my $path = File::Spec->catfile($base, File::Spec->splitdir($relative));
    my $record = canonical_file($path, $label, MAX_CORPUS_ARTIFACT_BYTES);
    my $root = "$base/";
    die "$label escapes its evidence root\n" unless index($record->{path}, $root) == 0;
    verify_descriptor_record($descriptor, $record, $label);
    return $record;
}

sub resolve_absolute_descriptor {
    my ($descriptor, $label) = @_;
    assert_exact_keys($descriptor, $label, qw(path sha256 size));
    my $record = canonical_file($descriptor->{path} // '', $label,
        MAX_CORPUS_ARTIFACT_BYTES);
    verify_descriptor_record($descriptor, $record, $label);
    return $record;
}

sub verify_descriptor_record {
    my ($descriptor, $record, $label) = @_;
    die "$label descriptor hash or size differs from retained bytes\n"
        unless ($descriptor->{sha256} // '') eq $record->{sha256}
            && defined($descriptor->{size}) && !ref($descriptor->{size})
            && $descriptor->{size} =~ /\A[0-9]+\z/
            && $descriptor->{size} == $record->{size};
}

sub load_strict_json {
    my ($record, $label) = @_;
    my $bytes = read_snapshot($record, $label);
    reject_duplicate_json_keys($bytes, $label);
    my $value = eval { JSON::PP->new->utf8->decode($bytes) };
    die "Invalid $label JSON\n" unless ref($value) eq 'HASH' && !$@;
    return $value;
}

sub authoritative_bridge {
    my ($directory, $file, $source_commit, $perl5_commit, $launch_bytes,
        $package, $make) = @_;
    my $identity = {
        source_commit => $source_commit,
        runner_commit => $source_commit,
        perl5_commit => $perl5_commit,
        jar_embedded_commit => $make->{identity}{jar_embedded_commit},
        jperl_sha256 => $file->{jperl}{sha256},
        jcpan_sha256 => $file->{jcpan}{sha256},
        git_sha256 => $file->{git}{sha256},
        jar_sha256 => $file->{jar}{sha256},
        sbom_sha256 => $file->{sbom}{sha256},
        baseline_sha256 => $file->{baseline}{sha256},
        corpus_manifest_sha256 => $file->{corpus_manifest}{sha256},
        requirements_sha256 => $file->{requirements}{sha256},
        cpan_policy_sha256 => $file->{cpan_policy}{sha256},
        package_evidence_sha256 => $file->{package_evidence}{sha256},
        make_evidence_sha256 => $file->{make_evidence}{sha256},
    };
    my $inputs = {
        source => { path => $directory->{source}, commit => $source_commit },
        perl5 => { path => $directory->{perl5}, commit => $perl5_commit },
        map { $_ => public_record($file->{$_}) } qw(jperl jcpan git jar sbom
            baseline corpus_manifest requirements cpan_policy package_evidence
            make_evidence),
    };
    my $evidence = {
        corpus => { path => $file->{corpus_manifest}{path},
            sha256 => $file->{corpus_manifest}{sha256} },
        package => { path => $file->{package_evidence}{path},
            sha256 => $file->{package_evidence}{sha256},
            identity => $package->{identity} },
        make => { path => $file->{make_evidence}{path},
            sha256 => $file->{make_evidence}{sha256},
            identity => $make->{identity} },
    };
    my $tuple_sha = Digest::SHA::sha256_hex(canonical({ identity => $identity,
        inputs => $inputs, evidence => $evidence }));
    return {
        schema_version => 1,
        kind => 'phase36-cpan-launch-bridge',
        authoritative => JSON::PP::true,
        authority_marker_required => JSON::PP::true,
        tuple_sha256 => $tuple_sha,
        launch_manifest => {
            path => $option{output}, sha256 => Digest::SHA::sha256_hex($launch_bytes),
            size => length($launch_bytes), schema => 'legacy-cpan-launch/v1',
        },
        identity => $identity,
        inputs => $inputs,
        evidence => $evidence,
    };
}

sub authority_marker {
    my ($path, $launch_bytes, $bridge_bytes, $seal_bytes, $tuple_sha) = @_;
    return {
        schema_version => 1,
        kind => 'phase36-cpan-launch-authority',
        authoritative => JSON::PP::true,
        tuple_sha256 => $tuple_sha,
        launch_manifest => { path => $path->{launch},
            sha256 => Digest::SHA::sha256_hex($launch_bytes),
            size => length($launch_bytes) },
        bridge => { path => $path->{bridge},
            sha256 => Digest::SHA::sha256_hex($bridge_bytes),
            size => length($bridge_bytes) },
        seal => { path => $path->{seal},
            sha256 => Digest::SHA::sha256_hex($seal_bytes),
            size => length($seal_bytes) },
    };
}

sub public_record {
    my ($record) = @_;
    return { path => $record->{path}, sha256 => $record->{sha256},
        size => $record->{size} };
}

sub canonical { return JSON::PP->new->utf8->canonical->encode($_[0]) }

sub validate_corpus_manifest {
    my ($manifest, $directory, $file, $source_commit, $perl5_commit) = @_;
    assert_exact_keys($manifest, 'corpus manifest', qw(schema_version mode source
        identity baseline artifact_directory expected_files strict_regex_expected_files
        verified_runner_sha ledger_summary strict_regex_ledger_summary commands
        exit_statuses artifacts));
    die "Corpus manifest schema_version must be 1\n"
        unless ($manifest->{schema_version} // 0) == 1;
    die "Corpus manifest mode must be acceptance\n"
        unless ($manifest->{mode} // '') eq 'acceptance';
    die "Corpus manifest path must be artifact_directory/manifest.json\n"
        unless basename($file->{corpus_manifest}{path}) eq 'manifest.json';
    my $artifact_directory = canonical_directory(
        $manifest->{artifact_directory} // '', 'corpus artifact directory');
    die "Corpus manifest artifact directory differs from its path\n"
        unless $artifact_directory eq dirname($file->{corpus_manifest}{path});
    die "Corpus manifest has no complete corpus rows\n"
        unless positive_integer($manifest->{expected_files});
    die "Corpus manifest has no strict regex rows\n"
        unless positive_integer($manifest->{strict_regex_expected_files});
    die "Strict regex row count exceeds complete corpus row count\n"
        if $manifest->{strict_regex_expected_files} > $manifest->{expected_files};

    my $source = $manifest->{source};
    die "Corpus source identity is missing\n" unless ref($source) eq 'HASH';
    assert_exact_keys($source, 'corpus source identity', qw(starting_sha final_sha
        perl5_sha_as_provenance tracked_state_signature));
    for my $field (qw(starting_sha final_sha)) {
        die "Corpus $field differs from selected source commit\n"
            unless ($source->{$field} // '') eq $source_commit;
    }
    die "Corpus perl5 provenance differs from selected perl5 commit\n"
        unless ($source->{perl5_sha_as_provenance} // '') eq $perl5_commit;
    die "Corpus tracked-state signature is malformed\n"
        unless ($source->{tracked_state_signature} // '') =~ /\A[0-9a-f]{64}\z/;
    die "Corpus tracked-state signature does not describe a clean checkout\n"
        unless $source->{tracked_state_signature} eq Digest::SHA::sha256_hex('');

    my $identity = $manifest->{identity};
    die "Corpus identity is missing\n" unless ref($identity) eq 'HASH';
    assert_exact_keys($identity, 'corpus identity', qw(source_commit runner_commit
        perl5_commit launcher jar sbom baseline));
    die "Corpus source identity differs from selected source commit\n"
        unless ($identity->{source_commit} // '') eq $source_commit;
    die "Corpus runner identity differs from selected source commit\n"
        unless ($identity->{runner_commit} // '') eq $source_commit
            && ($manifest->{verified_runner_sha} // '') eq $source_commit;
    die "Corpus perl5 identity differs from selected perl5 commit\n"
        unless ($identity->{perl5_commit} // '') eq $perl5_commit;
    for my $binding (
        [launcher => 'jperl'], [jar => 'jar'], [sbom => 'sbom'],
        [baseline => 'baseline'],
    ) {
        my ($identity_name, $file_name) = @$binding;
        my $descriptor = $identity->{$identity_name};
        die "Corpus $identity_name descriptor is missing\n"
            unless ref($descriptor) eq 'HASH';
        assert_exact_keys($descriptor, "corpus $identity_name descriptor",
            qw(path sha256));
        die "Corpus $identity_name path differs from selected input\n"
            unless ($descriptor->{path} // '') eq $file->{$file_name}{path};
        die "Corpus $identity_name hash differs from selected input\n"
            unless ($descriptor->{sha256} // '') eq $file->{$file_name}{sha256};
    }
    die "Corpus baseline path differs from selected input\n"
        unless ($manifest->{baseline} // '') eq $file->{baseline}{path};

    my @required_commands = qw(jperl-version ledger strict-regex-ledger
        jvm-runner interpreter-runner jvm-comparison interpreter-comparison
        jvm-strict-regex-comparison interpreter-strict-regex-comparison packaging);
    my $commands = $manifest->{commands};
    die "Corpus command records are missing\n" unless ref($commands) eq 'ARRAY';
    my %command;
    for my $record (@$commands) {
        die "Corpus command record is malformed\n" unless ref($record) eq 'HASH';
        assert_exact_keys($record, 'corpus command record', qw(name argv environment));
        my $name = $record->{name} // '';
        die "Corpus command name is missing or duplicated\n"
            unless length($name) && !$command{$name};
        die "Corpus command argv or environment is malformed: $name\n"
            unless ref($record->{argv}) eq 'ARRAY' && @{$record->{argv}}
                && ref($record->{environment}) eq 'HASH';
        $command{$name} = $record;
    }
    assert_exact_keys(\%command, 'corpus command set', @required_commands);
    my $statuses = $manifest->{exit_statuses};
    assert_exact_keys($statuses, 'corpus exit-status set', @required_commands);
    for my $name (@required_commands) {
        die "Corpus command did not complete successfully: $name\n"
            unless defined($statuses->{$name}) && !ref($statuses->{$name})
                && $statuses->{$name} eq '0';
    }
    my $version = $command{'jperl-version'};
    die "Corpus jperl identity command differs from selected launcher\n"
        unless @{$version->{argv}} == 2
            && $version->{argv}[0] eq $file->{jperl}{path}
            && $version->{argv}[1] eq '-v';
    assert_exact_keys($version->{environment}, 'corpus jperl identity environment',
        qw(JPERL_UNIMPLEMENTED PERLONJAVA_JAR));
    die "Corpus jperl identity command was not bound to the selected JAR\n"
        unless ($version->{environment}{PERLONJAVA_JAR} // '') eq $file->{jar}{path};
    die "Corpus jperl identity command enabled unimplemented-feature warnings\n"
        unless exists($version->{environment}{JPERL_UNIMPLEMENTED})
            && !defined($version->{environment}{JPERL_UNIMPLEMENTED});

    my $artifacts = $manifest->{artifacts};
    die "Corpus artifact descriptors are missing\n"
        unless ref($artifacts) eq 'HASH' && keys %$artifacts;
    my @required_artifacts = qw(regex-ledger.json regex-files.txt
        strict-regex-ledger.json regex-scope-files.txt strict-regex-files.txt
        jvm-results.json interpreter-results.json jvm-comparison.json
        interpreter-comparison.json jvm-strict-regex-comparison.json
        interpreter-strict-regex-comparison.json ledger.log
        strict-regex-ledger.log jvm-runner.log interpreter-runner.log
        jvm-comparison.log interpreter-comparison.log
        jvm-strict-regex-comparison.log interpreter-strict-regex-comparison.log
        packaging.log jperl-version.log);
    assert_exact_keys($artifacts, 'corpus artifact set', @required_artifacts);
    my $total = 0;
    my %seen_path;
    my @records;
    for my $name (sort keys %$artifacts) {
        die "Corpus artifact name is unsafe: $name\n"
            unless $name =~ /\A[A-Za-z0-9][A-Za-z0-9_.-]*\z/;
        my $descriptor = $artifacts->{$name};
        die "Corpus artifact descriptor is malformed: $name\n"
            unless ref($descriptor) eq 'HASH';
        assert_exact_keys($descriptor, "corpus artifact $name", qw(path sha256));
        my $record = canonical_file($descriptor->{path} // '',
            "corpus artifact $name", MAX_CORPUS_ARTIFACT_BYTES);
        die "Corpus artifact escapes the artifact directory: $name\n"
            unless dirname($record->{path}) eq $artifact_directory;
        die "Corpus artifact path is duplicated: $record->{path}\n"
            if $seen_path{$record->{path}}++;
        die "Corpus artifact basename differs from its key: $name\n"
            unless basename($record->{path}) eq $name;
        die "Corpus artifact hash mismatch: $name\n"
            unless ($descriptor->{sha256} // '') eq $record->{sha256};
        $total += $record->{size};
        die "Corpus artifact set exceeds the bounded byte budget\n"
            if $total > MAX_CORPUS_TOTAL_BYTES;
        push @records, $record;
    }
    my ($version_record) = grep { basename($_->{path}) eq 'jperl-version.log' }
        @records;
    my $version_text = read_snapshot($version_record, 'retained jperl version log');
    my @reported = $version_text =~ /\b([0-9a-f]{7,40})\b/ig;
    die "Retained jperl version log does not bind the selected source commit\n"
        unless grep { index($source_commit, lc($_)) == 0 } @reported;
    return \@records;
}

sub verify_all_inputs {
    my ($directory, $file, $corpus_artifacts, $source_commit, $perl5_commit) = @_;
    for my $name (sort keys %$file) {
        my $current = canonical_file($file->{$name}{path}, $name, $limit{$name});
        die "Selected input changed during launch-manifest preparation: $name\n"
            unless same_record($current, $file->{$name});
    }
    die "Source checkout changed during launch-manifest preparation\n"
        unless clean_checkout_commit($directory->{source}, 'source',
            $file->{git}{path}) eq $source_commit;
    die "perl5 checkout changed during launch-manifest preparation\n"
        unless clean_checkout_commit($directory->{perl5}, 'perl5',
            $file->{git}{path}) eq $perl5_commit;
    for my $record (@$corpus_artifacts) {
        my $current = canonical_file($record->{path}, 'corpus artifact',
            MAX_CORPUS_ARTIFACT_BYTES);
        die "Retained corpus artifact changed during launch-manifest preparation\n"
            unless same_record($current, $record);
    }
    verify_tracked_file($directory->{source}, 'jperl', $file->{git}{path});
    verify_tracked_file($directory->{source}, 'jcpan', $file->{git}{path});
}

sub canonical_file {
    my ($path, $label, $maximum) = @_;
    die "$label path must be absolute\n"
        unless defined($path) && File::Spec->file_name_is_absolute($path);
    my @stat = lstat($path);
    die "$label is missing\n" unless @stat;
    die "$label must be a nonsymlink regular file\n"
        unless -f _ && !-l _;
    my $resolved = abs_path($path);
    die "$label path must be canonical and contain no symlink components\n"
        unless defined($resolved) && $resolved eq $path;
    die "$label is empty\n" unless $stat[7] > 0;
    die "$label exceeds its bounded byte limit\n" if $stat[7] > $maximum;
    my $sha = sha256_file($path, $maximum, $label);
    my @after = lstat($path);
    die "$label changed while it was read\n"
        unless @after && !-l _ && $after[0] == $stat[0] && $after[1] == $stat[1]
            && $after[2] == $stat[2] && $after[7] == $stat[7]
            && $after[9] == $stat[9];
    return { path => $path, sha256 => $sha, size => 0 + $stat[7],
        dev => 0 + $stat[0], inode => 0 + $stat[1], mode => 0 + $stat[2],
        mtime => 0 + $stat[9] };
}

sub canonical_directory {
    my ($path, $label) = @_;
    die "$label path must be absolute\n"
        unless defined($path) && File::Spec->file_name_is_absolute($path);
    my @stat = lstat($path);
    die "$label is missing or is not a directory\n" unless @stat && -d _;
    die "$label must not be a symlink\n" if -l _;
    my $resolved = abs_path($path);
    die "$label path must be canonical and contain no symlink components\n"
        unless defined($resolved) && $resolved eq $path;
    return $resolved;
}

sub clean_checkout_commit {
    my ($directory, $label, $git) = @_;
    my $commit = capture_bounded([$git, '-C', $directory, 'rev-parse', '--verify',
        'HEAD^{commit}'], 256, "$label commit");
    $commit =~ s/\s+\z//;
    die "$label checkout HEAD is not a full Git commit\n"
        unless $commit =~ /\A[0-9a-f]{40}\z/;
    my $status = capture_bounded([$git, '-C', $directory, 'status', '--porcelain',
        '--untracked-files=no'], 1024 * 1024, "$label status");
    die "$label checkout tracked state is dirty\n" if length $status;
    return $commit;
}

sub verify_tracked_file {
    my ($source, $relative, $git) = @_;
    capture_bounded([$git, '-C', $source, 'ls-files', '--error-unmatch', '--',
        $relative], 4096, "tracked $relative");
}

sub capture_bounded {
    my ($argv, $maximum, $label) = @_;
    open my $fh, '-|', @$argv or die "Cannot execute $argv->[0]: $!\n";
    binmode $fh, ':raw';
    my $bytes = '';
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

sub sha256_file {
    my ($path, $maximum, $label) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $label: $!\n";
    my $digest = Digest::SHA->new(256);
    my $total = 0;
    while (1) {
        my $read = read($fh, my $chunk, 1024 * 1024);
        die "Cannot read $label: $!\n" unless defined $read;
        last unless $read;
        $total += $read;
        die "$label exceeds its bounded byte limit\n" if $total > $maximum;
        $digest->add($chunk);
    }
    close $fh or die "Cannot close $label: $!\n";
    return $digest->hexdigest;
}

sub read_snapshot {
    my ($record, $label) = @_;
    open my $fh, '<:raw', $record->{path} or die "Cannot read $label: $!\n";
    local $/;
    my $bytes = <$fh>;
    close $fh or die "Cannot close $label: $!\n";
    die "$label changed while it was read\n"
        unless length($bytes) == $record->{size}
            && Digest::SHA::sha256_hex($bytes) eq $record->{sha256};
    return $bytes;
}

sub same_record {
    my ($left, $right) = @_;
    return $left->{path} eq $right->{path}
        && $left->{sha256} eq $right->{sha256}
        && $left->{size} == $right->{size}
        && $left->{dev} == $right->{dev}
        && $left->{inode} == $right->{inode}
        && $left->{mode} == $right->{mode}
        && $left->{mtime} == $right->{mtime};
}

sub assert_exact_keys {
    my ($value, $label, @expected) = @_;
    die "$label must be an object\n" unless ref($value) eq 'HASH';
    my @actual = sort keys %$value;
    @expected = sort @expected;
    die "$label has missing, extra, or duplicate fields\n"
        unless JSON::PP->new->canonical->encode(\@actual)
            eq JSON::PP->new->canonical->encode(\@expected);
}

sub reject_duplicate_json_keys {
    my ($bytes, $label) = @_;
    pos($bytes) = 0;
    parse_json_value(\$bytes, 0, $label);
    skip_json_space(\$bytes);
    die "$label has trailing JSON data\n" unless pos($bytes) == length($bytes);
}

sub parse_json_value {
    my ($source, $depth, $label) = @_;
    die "$label exceeds the maximum JSON nesting depth\n"
        if $depth > MAX_JSON_DEPTH;
    skip_json_space($source);
    my $next = substr($$source, pos($$source), 1);
    if ($next eq '{') {
        pos($$source)++;
        skip_json_space($source);
        my %seen;
        return pos($$source)++ if substr($$source, pos($$source), 1) eq '}';
        while (1) {
            my $key = parse_json_string($source, $label);
            die "$label contains duplicate JSON key: $key\n" if $seen{$key}++;
            skip_json_space($source);
            die "$label has malformed JSON object syntax\n"
                unless substr($$source, pos($$source)++, 1) eq ':';
            parse_json_value($source, $depth + 1, $label);
            skip_json_space($source);
            my $separator = substr($$source, pos($$source)++, 1);
            last if $separator eq '}';
            die "$label has malformed JSON object syntax\n" unless $separator eq ',';
            skip_json_space($source);
        }
        return;
    }
    if ($next eq '[') {
        pos($$source)++;
        skip_json_space($source);
        return pos($$source)++ if substr($$source, pos($$source), 1) eq ']';
        while (1) {
            parse_json_value($source, $depth + 1, $label);
            skip_json_space($source);
            my $separator = substr($$source, pos($$source)++, 1);
            last if $separator eq ']';
            die "$label has malformed JSON array syntax\n" unless $separator eq ',';
        }
        return;
    }
    if ($next eq '"') {
        parse_json_string($source, $label);
        return;
    }
    $$source =~ /\G(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/gc
        or die "$label has malformed JSON value\n";
}

sub parse_json_string {
    my ($source, $label) = @_;
    my $start = pos($$source);
    die "$label has malformed JSON string\n"
        unless substr($$source, $start, 1) eq '"';
    pos($$source)++;
    while (pos($$source) < length($$source)) {
        my $character = substr($$source, pos($$source)++, 1);
        if ($character eq '"') {
            my $token = substr($$source, $start, pos($$source) - $start);
            my $decoded = eval { JSON::PP->new->decode($token) };
            die "$label has malformed JSON string\n" if $@ || ref($decoded);
            return $decoded;
        }
        die "$label has a control character in a JSON string\n"
            if ord($character) < 0x20;
        if ($character eq '\\') {
            my $escape = substr($$source, pos($$source)++, 1);
            die "$label has malformed JSON escape\n" unless $escape =~ /["\\\/bfnrtu]/;
            if ($escape eq 'u') {
                my $hex = substr($$source, pos($$source), 4);
                die "$label has malformed JSON Unicode escape\n"
                    unless $hex =~ /\A[0-9a-fA-F]{4}\z/;
                pos($$source) += 4;
            }
        }
    }
    die "$label has unterminated JSON string\n";
}

sub skip_json_space {
    my ($source) = @_;
    $$source =~ /\G[\x20\x09\x0a\x0d]*/gc;
}

sub positive_integer {
    return defined($_[0]) && !ref($_[0]) && $_[0] =~ /\A[1-9][0-9]*\z/;
}

sub validate_cli_tokens {
    my ($argv) = @_;
    my %takes_value = map { $_ => 1 } qw(source-dir perl5-dir jperl jcpan jar
        sbom baseline corpus-manifest requirements cpan-policy package-evidence
        make-evidence git output);
    my %seen;
    for (my $index = 0; $index < @$argv; ++$index) {
        my $token = $argv->[$index];
        die "Unknown option syntax: $token\n"
            unless $token =~ /\A--([^=]+)(?:=(.*))?\z/s;
        my ($name, $inline) = ($1, $2);
        if ($name ne 'help' && !$takes_value{$name}) {
            warn "Unknown option: $name\n";
            usage(2);
        }
        die "Duplicate option --$name\n" if $seen{$name}++;
        if ($name eq 'help') {
            die "--help does not take a value\n" if defined $inline;
            next;
        }
        if (!defined $inline) {
            die "Option --$name requires a value\n"
                if $index + 1 >= @$argv || $argv->[$index + 1] =~ /\A--/;
            ++$index;
        }
    }
}

sub locate_legacy_git {
    for my $directory (split /:/, ($ENV{PATH} // '')) {
        next unless length $directory;
        my $candidate = File::Spec->catfile($directory, 'git');
        next unless -f $candidate && -x $candidate;
        my $resolved = abs_path($candidate);
        return $resolved if defined $resolved && -f $resolved && -x $resolved;
    }
    die "Legacy compatibility mode cannot locate git\n";
}

sub publish_legacy {
    my ($output, $bytes, $verify) = @_;
    my $parent = dirname($output);
    my $stage = File::Spec->catfile($parent,
        '.' . basename($output) . ".legacy-stage.$$");
    my ($owned, $stage_record, $success);
    my $ok = eval {
        $stage_record = write_exclusive_synced($stage, $bytes,
            'legacy launch manifest stage');
        $verify->();
        my %record;
        publish_no_replace($stage, $output, \%record, 'launch');
        $owned = $record{launch};
        sync_directory($parent, 'legacy launch directory after publication');
        publication_failpoint('after-legacy-sync');
        $verify->();
        safe_unlink($stage_record, 'legacy staging link');
        sync_directory($parent, 'legacy launch directory after staging cleanup');
        $success = 1;
        1;
    };
    my $error = $@;
    if (!$ok || !$success) {
        my @cleanup_error;
        if ($owned) {
            my @now = lstat($output);
            if (@now && $now[0] == $owned->{dev}
                    && $now[1] == $owned->{inode}) {
                unlink $output
                    or push @cleanup_error, "Cannot roll back legacy launch: $!\n";
            }
        }
        eval { safe_unlink_if_owned($stage, 'legacy staging rollback',
            $stage_record) };
        push @cleanup_error, $@ if $@;
        eval { sync_directory($parent, 'legacy launch directory after rollback') };
        push @cleanup_error, $@ if $@;
        die $error . join('', @cleanup_error);
    }
}

sub publish_bundle {
    my ($paths, $bytes, $verify) = @_;
    my $parent = dirname($paths->{launch});
    my (%stage, %owned);
    my $success = eval {
        for my $name (qw(launch bridge seal authority)) {
            my $path = File::Spec->catfile($parent,
                '.' . basename($paths->{$name}) . ".stage.$$.$name");
            $stage{$name} = write_exclusive_synced($path, $bytes->{$name},
                "staged $name");
        }
        for my $name (qw(launch bridge seal)) {
            publish_no_replace($stage{$name}{path}, $paths->{$name}, \%owned, $name);
        }
        sync_directory($parent, 'launch-manifest directory after sidecars');
        publication_failpoint('after-sidecars-sync');
        $verify->();
        publish_no_replace($stage{authority}{path}, $paths->{authority}, \%owned,
            'authority marker');
        publication_failpoint('after-authority-link');
        sync_directory($parent, 'launch-manifest directory after authority');
        publication_failpoint('after-authority-sync');
        $verify->();
        for my $name (qw(launch bridge seal authority)) {
            safe_unlink($stage{$name}, "staged $name link");
            delete $stage{$name};
        }
        sync_directory($parent, 'launch-manifest directory after staging cleanup');
        1;
    };
    my $error = $@;
    if (!$success) {
        my @cleanup_error;
        for my $name (reverse qw(authority seal bridge launch)) {
            next unless $owned{$name};
            my @now = lstat($paths->{$name});
            if (@now && $now[0] == $owned{$name}{dev}
                    && $now[1] == $owned{$name}{inode}) {
                unlink $paths->{$name}
                    or push @cleanup_error, "Cannot roll back $name: $!";
            }
        }
        for my $record (values %stage) {
            eval { safe_unlink_if_owned($record->{path}, 'staging rollback',
                $record) };
            push @cleanup_error, $@ if $@;
        }
        eval { sync_directory($parent,
            'launch-manifest directory after rollback') };
        push @cleanup_error, $@ if $@;
        die $error . (@cleanup_error ? join("\n", @cleanup_error) . "\n" : '');
    }
}

sub write_exclusive_synced {
    my ($path, $bytes, $label) = @_;
    sysopen my $fh, $path, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot exclusively create $label: $!\n";
    my @stat = lstat($path);
    my $record = { path => $path, dev => 0 + $stat[0], inode => 0 + $stat[1] };
    my $ok = eval {
        binmode $fh, ':raw';
        print {$fh} $bytes or die "Cannot write $label: $!\n";
        $fh->flush or die "Cannot flush $label: $!\n";
        $fh->sync or die "Cannot fsync $label: $!\n";
        close $fh or die "Cannot close $label: $!\n";
        1;
    };
    my $error = $@;
    if (!$ok) {
        eval { close $fh };
        safe_unlink_if_owned($path, "$label cleanup", $record);
        die $error;
    }
    return $record;
}

sub publish_no_replace {
    my ($stage, $final, $owned, $label) = @_;
    link $stage, $final
        or die "Cannot exclusively publish $label without replacement: $!\n";
    my @stat = lstat($final);
    die "Published $label is not a regular nonsymlink file\n"
        unless @stat && -f _ && !-l _;
    my @stage_stat = lstat($stage);
    die "Published $label does not retain staged identity\n"
        unless @stage_stat && $stage_stat[0] == $stat[0]
            && $stage_stat[1] == $stat[1];
    $owned->{$label eq 'authority marker' ? 'authority' : $label} = {
        dev => 0 + $stat[0], inode => 0 + $stat[1] };
}

sub safe_unlink {
    my ($record, $label) = @_;
    my @stat = lstat($record->{path});
    die "$label identity changed before cleanup\n"
        unless @stat && $stat[0] == $record->{dev}
            && $stat[1] == $record->{inode};
    unlink $record->{path} or die "Cannot remove $label: $!\n";
}

sub safe_unlink_if_owned {
    my ($path, $label, $record) = @_;
    return unless -e $path || -l $path;
    my @stat = lstat($path);
    return unless @stat;
    if ($record && ($stat[0] != $record->{dev} || $stat[1] != $record->{inode})) {
        die "$label identity changed; refusing unsafe cleanup\n";
    }
    unlink $path or die "Cannot remove $label: $!\n";
}

sub sync_directory {
    my ($directory, $label) = @_;
    sysopen my $fh, $directory, O_RDONLY
        or die "Cannot open $label: $!\n";
    $fh->sync or die "Cannot fsync $label: $!\n";
    close $fh or die "Cannot close $label: $!\n";
}

sub publication_failpoint {
    my ($name) = @_;
    my $selected = $ENV{PHASE36_CPAN_BRIDGE_TEST_FAILPOINT} // '';
    die "Phase 36 CPAN bridge failpoint: $name\n" if $selected eq $name;
}

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: prepare_phase36_cpan_launch_manifest.pl \
  --source-dir ABS --perl5-dir ABS --jperl ABS --jcpan ABS \
  --jar ABS --sbom ABS --baseline ABS --corpus-manifest ABS \
  --requirements ABS --cpan-policy ABS --package-evidence ABS \
  --make-evidence ABS --git ABS --output ABS

Create the canonical, fail-closed schema_version 1 launch manifest consumed by
run_phase36_cpan_acceptance.pl. Every path must be canonical and absolute. The
completed regex corpus, package, and make records supply evidence that this tool
rereads, hashes, strictly validates, and cross-binds. The legacy CPAN input is
published unchanged, durable bridge sidecars follow, and the no-replace
authority marker is published last. It never executes jperl or jcpan.
USAGE
    exit $status;
}
