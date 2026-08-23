use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IO::Compress::Zip qw($ZipError);
use JSON::PP;
use Test::More;

my $root = abs_path(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $producer = File::Spec->catfile($root, 'dev', 'tools',
    'prepare_phase36_cpan_launch_manifest.pl');
my $consumer = File::Spec->catfile($root, 'dev', 'tools',
    'run_phase36_cpan_acceptance.pl');
my $real_git = find_program('git');
my $system_perl = find_program('perl');
my $tmp = abs_path(tempdir(CLEANUP => 1));
my $source = repository(File::Spec->catdir($tmp, 'source'));
my $perl5 = repository(File::Spec->catdir($tmp, 'perl5'));
my $source_tools = File::Spec->catdir($source, 'dev', 'tools');
make_path($source_tools);
my $package_producer = File::Spec->catfile($source_tools,
    'run_phase36_package_evidence.pl');
my $make_producer = File::Spec->catfile($source_tools,
    'run_phase36_make_evidence.pl');
my $jperl = File::Spec->catfile($source, 'jperl');
my $jcpan = File::Spec->catfile($source, 'jcpan');
write_file($package_producer,
    "#!/usr/bin/env perl\n# canonical fixture package producer\n");
write_file($make_producer,
    "#!/usr/bin/env perl\n# canonical fixture make producer\n");
write_file($jperl, "#!/bin/sh\nexec '$real_git' -C '$source' rev-parse HEAD\n");
write_file($jcpan, <<'JCPAN');
#!/bin/sh
printf 'fixture.t .. ok\nAll tests successful.\nFiles=1, Tests=1, 0 wallclock secs\nResult: PASS\n'
JCPAN
chmod 0755, $jperl, $jcpan or die 'chmod fixture launchers';
git_commit_files($source, 'canonical launch fixture', qw(jperl jcpan),
    'dev/tools/run_phase36_package_evidence.pl',
    'dev/tools/run_phase36_make_evidence.pl');
my $source_commit = head($source);
my $perl5_commit = head($perl5);

my $inputs = File::Spec->catdir($tmp, 'inputs');
my $corpus_dir = File::Spec->catdir($tmp, 'corpus');
my $authority_dir = File::Spec->catdir($tmp, 'authority');
my $outputs = File::Spec->catdir($tmp, 'outputs');
make_path($inputs, $corpus_dir, $authority_dir, $outputs);
my $jar = File::Spec->catfile($inputs, 'perlonjava.jar');
my $sbom = File::Spec->catfile($inputs, 'sbom.json');
my $baseline = File::Spec->catfile($inputs, 'baseline.log');
my $requirements = File::Spec->catfile($inputs, 'requirements.json');
my $policy = File::Spec->catfile($root, 'dev', 'tools',
    'phase36_cpan_targets.json');
my $corpus = File::Spec->catfile($corpus_dir, 'manifest.json');
write_file($baseline, "fixture baseline\n");
write_jar_sbom($source_commit, 1);
write_requirements();
write_corpus();
my ($package_evidence, $make_evidence) = write_authority();

my $legacy = File::Spec->catfile($outputs, 'prepare-only.json');
my ($legacy_status, $legacy_text) = invoke_producer($legacy, strict => 0);
is($legacy_status, 0, 'compatibility producer succeeds only as prepare-only');
is(read_json($legacy)->{mode}, 'prepare-only',
    'compatibility launch is distinctly non-authoritative');
my ($legacy_accept_status, $legacy_accept_text) = run_command($^X, $consumer,
    '--manifest', $legacy, '--evidence-dir', File::Spec->catdir($tmp, 'legacy-run'));
isnt($legacy_accept_status, 0, 'acceptance rejects a direct compatibility launch');
like($legacy_accept_text, qr/requires --authority-marker as its sole manifest authority/,
    'compatibility rejection identifies the sole authority entry point');

my $output = File::Spec->catfile($outputs, 'launch.json');
my ($status, $text) = invoke_producer($output, strict => 1);
is($status, 0, 'strict producer publishes a canonical authority bundle')
    or diag $text;
my $bridge = read_json("$output.bridge.json");
my $marker = read_json("$output.authority.json");
is($bridge->{inputs}{package_producer}{path}, $package_producer,
    'bridge binds canonical tracked package producer path');
is($bridge->{inputs}{make_producer}{path}, $make_producer,
    'bridge binds canonical tracked make producer path');
is($bridge->{identity}{actual_jar_embedded_commit}, $source_commit,
    'bridge records independently inspected JAR commit');
is($marker->{tuple_sha256}, $bridge->{tuple_sha256},
    'authority marker seals the canonical bridge tuple');

my $acceptance_dir = File::Spec->catdir($tmp, 'acceptance');
my ($accept_status, $accept_text) = run_command($^X, $consumer,
    '--authority-marker', "$output.authority.json",
    '--evidence-dir', $acceptance_dir);
is($accept_status, 0, 'CPAN consumer accepts only the complete authority bundle')
    or diag $accept_text;
my $aggregate = read_json(File::Spec->catfile($acceptance_dir,
    'cpan-acceptance.json'));
is($aggregate->{authority}{tuple_sha256}, $bridge->{tuple_sha256},
    'aggregate evidence propagates exact authority tuple');
is($aggregate->{identity}{authority_marker_sha256},
    hash_file("$output.authority.json"),
    'aggregate evidence propagates exact authority marker hash');
my $mode_count = 0;
for my $target (values %{$aggregate->{results}}) {
    for my $mode (values %{$target->{modes}}) {
        ++$mode_count;
        is($mode->{identity}{authority_tuple_sha256}, $bridge->{tuple_sha256},
            'mode evidence propagates exact authority tuple');
        is($mode->{identity}{authority_bridge_sha256},
            hash_file("$output.bridge.json"),
            'mode evidence propagates exact bridge hash');
    }
}
ok($mode_count > 0, 'authority propagation covers executed mode evidence');

subtest 'consumer validates marker, bridge, and external seals fail-closed' => sub {
    my $bridge_output = File::Spec->catfile($outputs, 'consumer-bridge-mutation.json');
    my ($bridge_status, $bridge_text) = invoke_producer($bridge_output, strict => 1);
    is($bridge_status, 0, 'bridge-mutation fixture authority is published')
        or diag $bridge_text;
    write_file("$bridge_output.bridge.json",
        read_file("$bridge_output.bridge.json") . " \n");
    my ($bad_bridge_status, $bad_bridge_text) = run_command($^X, $consumer,
        '--authority-marker', "$bridge_output.authority.json",
        '--evidence-dir', File::Spec->catdir($tmp, 'bad-bridge-consumption'));
    isnt($bad_bridge_status, 0, 'mutated bridge is rejected before execution');
    like($bad_bridge_text, qr/authority bridge descriptor differs/,
        'bridge mutation is diagnosed at the marker boundary');

    my $seal_output = File::Spec->catfile($outputs, 'consumer-seal-mutation.json');
    my ($seal_status, $seal_text) = invoke_producer($seal_output, strict => 1);
    is($seal_status, 0, 'seal-mutation fixture authority is published')
        or diag $seal_text;
    write_file("$seal_output.bridge.sha256", "invalid\n");
    my ($bad_seal_status, $bad_seal_text) = run_command($^X, $consumer,
        '--authority-marker', "$seal_output.authority.json",
        '--evidence-dir', File::Spec->catdir($tmp, 'bad-seal-consumption'));
    isnt($bad_seal_status, 0, 'mutated bridge seal is rejected before execution');
    like($bad_seal_text, qr/authority seal descriptor differs/,
        'seal mutation is diagnosed at the marker boundary');

    my ($duplicate_status, $duplicate_text) = run_command($^X, $consumer,
        '--authority-marker', "$output.authority.json",
        '--authority-marker', "$output.authority.json",
        '--evidence-dir', File::Spec->catdir($tmp, 'duplicate-marker'));
    isnt($duplicate_status, 0, 'duplicate authority marker option is rejected');
    like($duplicate_text, qr/Duplicate option --authority-marker/,
        'duplicate marker rejection is exact');
};

subtest 'canonical producers and their external authority are fail-closed' => sub {
    ($package_evidence, $make_evidence) = write_authority('self-producer');
    my $bad = File::Spec->catfile($outputs, 'self-producer.json');
    my ($bad_status, $bad_text) = invoke_producer($bad, strict => 1);
    isnt($bad_status, 0, 'fixture cannot self-declare bridge as make producer');
    like($bad_text, qr/not the canonical tracked producer/,
        'producer mismatch has an authentication diagnostic');
    ok(!-e "$bad.authority.json", 'producer mismatch publishes no authority');

    ($package_evidence, $make_evidence) = write_authority('bad-make-seal');
    my $seal_bad = File::Spec->catfile($outputs, 'bad-make-seal.json');
    my ($seal_status, $seal_text) = invoke_producer($seal_bad, strict => 1);
    isnt($seal_status, 0, 'invalid external make seal is rejected');
    like($seal_text, qr/Make external seal is invalid/,
        'external make seal controls authority');
    ok(!-e "$seal_bad.authority.json", 'invalid make seal publishes no authority');
    ($package_evidence, $make_evidence) = write_authority();
};

subtest 'actual JAR and SBOM bytes override retained JSON claims' => sub {
    write_jar_sbom('d' x 40, 1);
    write_corpus();
    ($package_evidence, $make_evidence) = write_authority();
    my $bad_commit = File::Spec->catfile($outputs, 'actual-commit.json');
    my ($commit_status, $commit_text) = invoke_producer($bad_commit, strict => 1);
    isnt($commit_status, 0, 'claimed commit cannot override Configuration.class bytes');
    like($commit_text, qr/embedded commit differs from selected source commit/,
        'actual embedded commit controls rejection');
    ok(!-e "$bad_commit.authority.json", 'actual commit mismatch publishes no authority');

    write_jar_sbom($source_commit, 0);
    write_corpus();
    ($package_evidence, $make_evidence) = write_authority();
    my $bad_sbom = File::Spec->catfile($outputs, 'actual-sbom.json');
    my ($sbom_status, $sbom_text) = invoke_producer($bad_sbom, strict => 1);
    isnt($sbom_status, 0, 'claimed relation cannot override selected SBOM bytes');
    like($sbom_text, qr/canonical Joni fork component|dependency relation/,
        'actual SBOM relation controls rejection');
    ok(!-e "$bad_sbom.authority.json", 'actual SBOM mismatch publishes no authority');
    write_jar_sbom($source_commit, 1);
    write_corpus();
    ($package_evidence, $make_evidence) = write_authority();
};

subtest 'consumer reauthenticates immediately before accepted execution' => sub {
    my $late_output = File::Spec->catfile($outputs, 'late-authority.json');
    my ($fresh_status, $fresh_text) = invoke_producer($late_output, strict => 1);
    is($fresh_status, 0, 'fresh authority is sealed before late mutation')
        or diag $fresh_text;
    my $late = read_file($package_producer) . "# late mutation\n";
    write_file($package_producer, $late);
    my $late_dir = File::Spec->catdir($tmp, 'late-consumption');
    my ($late_status, $late_text) = run_command($^X, $consumer,
        '--authority-marker', "$late_output.authority.json", '--evidence-dir', $late_dir);
    isnt($late_status, 0, 'late canonical producer mutation is rejected');
    like($late_text, qr/package_producer input descriptor differs|tracked state is dirty/,
        'consumption-time producer authentication is explicit');
    ok(!-e File::Spec->catfile($late_dir, 'cpan-acceptance.json'),
        'late mutation emits no accepted aggregate');
};

done_testing;

sub invoke_producer {
    my ($out, %arg) = @_;
    my @argv = ($^X, $producer,
        '--source-dir', $source, '--perl5-dir', $perl5,
        '--jperl', $jperl, '--jcpan', $jcpan, '--jar', $jar, '--sbom', $sbom,
        '--baseline', $baseline, '--corpus-manifest', $corpus);
    push @argv, ('--requirements', $requirements, '--cpan-policy', $policy,
        '--package-evidence', $package_evidence, '--make-evidence', $make_evidence,
        '--git', $real_git) if $arg{strict};
    push @argv, '--output', $out;
    return run_command(@argv);
}

sub write_jar_sbom {
    my ($embedded_commit, $valid_relation) = @_;
    my $joni_ref = 'pkg:generic/perlonjava/joni-fork@2.2.7';
    my $jcodings_ref = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';
    my $document = {
        '$schema' => 'http://cyclonedx.org/schema/bom-1.6.schema.json',
        bomFormat => 'CycloneDX', specVersion => '1.6', serialNumber => 'fixture',
        version => 1, metadata => { component => { 'bom-ref' => 'perlonjava' } },
        components => $valid_relation ? [
            { group => 'org.perlonjava.fork', name => 'joni-fork', version => '2.2.7',
              'bom-ref' => $joni_ref, properties => [
                { name => 'perlonjava:source-commit', value => $source_commit } ] },
            { group => 'org.jruby.jcodings', name => 'jcodings', version => '1.0.64',
              'bom-ref' => $jcodings_ref },
        ] : [],
        dependencies => $valid_relation ? [
            { ref => 'perlonjava', dependsOn => [$joni_ref] },
            { ref => $joni_ref, dependsOn => [$jcodings_ref] },
        ] : [],
    };
    write_json($sbom, $document);
    my $zip = IO::Compress::Zip->new($jar,
        Name => 'org/perlonjava/core/Configuration.class')
        or die "Cannot create fixture JAR: $ZipError";
    print {$zip} "fixture Configuration commit $embedded_commit\n";
    for my $member (
        ['META-INF/sbom/sbom.json', read_file($sbom)],
        ['org/perlonjava/internal/joni/Regex.class', "joni\n"],
        ['org/perlonjava/internal/jcodings/Encoding.class', "jcodings\n"],
    ) {
        $zip->newStream(Name => $member->[0])
            or die "Cannot add fixture JAR member: $ZipError";
        print {$zip} $member->[1];
    }
    $zip->close or die "Cannot close fixture JAR: $ZipError";
}

sub write_authority {
    my ($variant) = @_;
    my $package_root = File::Spec->catdir($authority_dir, 'package-' . ++$::authority_id);
    make_path(File::Spec->catdir($package_root, 'package', 'logs'));
    my %retained = (
        jar => File::Spec->catfile($package_root, 'package', 'perlonjava.jar'),
        sbom => File::Spec->catfile($package_root, 'package', 'sbom.json'),
        deb => File::Spec->catfile($package_root, 'package', 'perlonjava.deb'),
        java_bom => File::Spec->catfile($package_root, 'package', 'bom.json'),
        perl_bom => File::Spec->catfile($package_root, 'package', 'perl-bom.json'),
        notice => File::Spec->catfile($package_root, 'package', 'notice.json'),
        log => File::Spec->catfile($package_root, 'package', 'logs', '001.log'),
        report => File::Spec->catfile($package_root, 'package', 'package-evidence-report.json'),
    );
    write_file($retained{jar}, read_file($jar));
    write_file($retained{sbom}, read_file($sbom));
    write_file($retained{$_}, "$_ fixture\n") for qw(deb java_bom perl_bom notice log);
    my $report = {
        schema_version => 1, kind => 'phase36-package-evidence-report',
        producer => 'run_phase36_package_evidence.pl', mode => 'acceptance',
        authoritative => JSON::PP::false, status => 'pass',
        verified => JSON::PP::true, missing_entries => 0, duplicate_entries => 0,
        jar_sha256 => hash_file($jar), sbom_sha256 => hash_file($sbom),
        identity => { source_root => $source, source_commit => $source_commit,
            jar_sha256 => hash_file($jar), sbom_sha256 => hash_file($sbom) },
        build_contract => {}, tools => {}, verifiers => {}, configs => [],
        immutable_inputs => [], package => {}, commands => [], artifacts => [],
        sbom_relation => { java_bom_sha256 => hash_file($retained{java_bom}),
            perl_bom_sha256 => hash_file($retained{perl_bom}),
            sbom_sha256 => hash_file($retained{sbom}),
            relation => 'java-components+joni-fork+perl-components',
            verified => JSON::PP::true },
        trees => {}, notice_license => {}, notice_license_artifact => {},
        retained_artifacts => {},
    };
    write_json($retained{report}, $report);
    my $package_path = File::Spec->catfile($package_root, 'package-evidence.json');
    write_json($package_path, {
        schema_version => 1, kind => 'packaging',
        producer => 'run_phase36_package_evidence.pl', verified => JSON::PP::true,
        identity => { source_commit => $source_commit,
            jar_sha256 => hash_file($jar), sbom_sha256 => hash_file($sbom) },
        completion => clean_completion(0),
        artifacts => {
            report => relative_descriptor($retained{report}, $package_root),
            deliverables => { map { $_ => relative_descriptor($retained{$_}, $package_root) }
                qw(jar sbom deb) },
            sbom_inputs => { java_bom => relative_descriptor($retained{java_bom}, $package_root),
                perl_bom => relative_descriptor($retained{perl_bom}, $package_root) },
            logs => { first => relative_descriptor($retained{log}, $package_root) },
            notice_license => relative_descriptor($retained{notice}, $package_root),
        }, missing_entries => 0, duplicate_entries => 0,
    });

    my $make_root = File::Spec->catdir($authority_dir, 'make-' . $::authority_id);
    make_path($make_root);
    my $embedded = File::Spec->catfile($make_root, 'embedded.json');
    write_json($embedded, { method => 'bounded-direct-content-scan', argv => [],
        archive_tool => descriptor($real_git), capture_sha256 => hash_file($jar),
        capture_size => -s $jar, jar_sha256 => hash_file($jar),
        resolved_commit => $source_commit });
    my %make_artifact = (jar => $jar, jar_embedded => $embedded);
    for my $name (qw(jar_version make_log source_after source_before tool_versions)) {
        my $path = File::Spec->catfile($make_root, "$name.txt");
        write_file($path, "$name\n");
        $make_artifact{$name} = $path;
    }
    my %tool_record;
    for my $name (qw(git jar_tool java make perl shell)) {
        my $path = $name eq 'git' ? $real_git : $system_perl;
        $tool_record{$name} = { %{descriptor($path)}, version_sha256 => '1' x 64 };
    }
    $tool_record{producer} = descriptor($variant && $variant eq 'self-producer'
        ? $producer : $make_producer);
    my %input_record = map { $_ => descriptor($jperl) } qw(build_gradle
        gradle_wrapper_jar gradle_wrapper_properties gradlew makefile settings_gradle);
    my $make_path = File::Spec->catfile($make_root, 'make-evidence.json');
    my $make_doc = {
        schema => 'perlonjava.phase36.make-evidence/v1', schema_version => 1,
        kind => 'make', producer => 'run_phase36_make_evidence.pl',
        mode => 'acceptance', status => 'pass', verified => JSON::PP::true,
        authoritative => JSON::PP::true,
        identity => { source_commit => $source_commit, runner_commit => $source_commit,
            jar_sha256 => hash_file($jar), jar_reported_commit => $source_commit,
            jar_embedded_commit => $source_commit },
        source => { root => $source, before => source_state(), after => source_state() },
        command => { cwd => $source, argv => [$system_perl], environment => {},
            started_utc => '2026-08-23T00:00:00Z',
            finished_utc => '2026-08-23T00:00:01Z', duration_milliseconds => 1 },
        tools => \%tool_record, inputs => \%input_record,
        completion => clean_completion(1),
        warning_scan => clean_scan($make_artifact{make_log}),
        failure_scan => clean_scan($make_artifact{make_log}),
        artifacts => { map { $_ => descriptor($make_artifact{$_}) }
            sort keys %make_artifact },
    };
    $make_doc->{seal} = { algorithm => 'SHA-256',
        payload_sha256 => sha256_hex(canonical($make_doc)) };
    write_json($make_path, $make_doc);
    my $seal = 'SHA-256 ' . $make_doc->{seal}{payload_sha256} . ' '
        . hash_file($make_path) . "\n";
    $seal = "invalid\n" if $variant && $variant eq 'bad-make-seal';
    write_file("$make_path.seal", $seal);
    return ($package_path, $make_path);
}

sub write_requirements {
    my $policy_document = read_json($policy);
    write_json($requirements, {
        schema_version => 1, policy => 'fixture',
        baseline_sha256 => hash_file($baseline), performance_acceptance => {},
        cpan_acceptance => { policy_sha256 => hash_file($policy),
            expected_targets => $policy_document->{expected_targets},
            required_modes => [qw(jvm interpreter)] },
        allowed_cpan_excluded_audit_classifications => [],
        required_ci_platforms => [], required_gates => [],
    });
}

sub write_corpus {
    my @names = qw(regex-ledger.json regex-files.txt strict-regex-ledger.json
        regex-scope-files.txt strict-regex-files.txt jvm-results.json
        interpreter-results.json jvm-comparison.json interpreter-comparison.json
        jvm-strict-regex-comparison.json interpreter-strict-regex-comparison.json
        ledger.log strict-regex-ledger.log jvm-runner.log interpreter-runner.log
        jvm-comparison.log interpreter-comparison.log jvm-strict-regex-comparison.log
        interpreter-strict-regex-comparison.log packaging.log jperl-version.log);
    my %path = map { $_ => File::Spec->catfile($corpus_dir, $_) } @names;
    write_file($path{$_}, "$_ fixture\n") for @names;
    write_file($path{'jperl-version.log'}, "PerlOnJava $source_commit\n");
    my @commands = qw(jperl-version ledger strict-regex-ledger jvm-runner
        interpreter-runner jvm-comparison interpreter-comparison
        jvm-strict-regex-comparison interpreter-strict-regex-comparison packaging);
    write_json($corpus, {
        schema_version => 1, mode => 'acceptance',
        source => { starting_sha => $source_commit, final_sha => $source_commit,
            perl5_sha_as_provenance => $perl5_commit,
            tracked_state_signature => sha256_hex('') },
        identity => { source_commit => $source_commit, runner_commit => $source_commit,
            perl5_commit => $perl5_commit, launcher => file_binding($jperl),
            jar => file_binding($jar), sbom => file_binding($sbom),
            baseline => file_binding($baseline) },
        baseline => $baseline, artifact_directory => $corpus_dir,
        expected_files => 623, strict_regex_expected_files => 84,
        verified_runner_sha => $source_commit, ledger_summary => {},
        strict_regex_ledger_summary => {},
        commands => [map { $_ eq 'jperl-version'
            ? { name => $_, argv => [$jperl, '-v'], environment => {
                JPERL_UNIMPLEMENTED => undef, PERLONJAVA_JAR => $jar } }
            : { name => $_, argv => [$^X], environment => {} } } @commands],
        exit_statuses => { map { $_ => 0 } @commands },
        artifacts => { map { $_ => file_binding($path{$_}) } @names },
    });
}

sub clean_completion {
    my ($truncated) = @_;
    my $value = { exit_code => 0, signal => 0, timeout => JSON::PP::false,
        incomplete => JSON::PP::false, review_stop => JSON::PP::false };
    $value->{truncated} = JSON::PP::false if $truncated;
    return $value;
}
sub clean_scan { { classifier => 'fixture', classifier_sha256 => '2' x 64,
    complete_log_sha256 => hash_file($_[0]), count => 0, matches => [] } }
sub source_state { { all_status_sha256 => '4' x 64, diff_sha256 => sha256_hex(''),
    status_sha256 => sha256_hex(''), head => $source_commit,
    tracked_clean => JSON::PP::true, extras => { authority_inputs => [],
        generated_file_count => 0, generated_paths => [], generated_total_bytes => 0 } } }
sub descriptor { { path => $_[0], sha256 => hash_file($_[0]), size => -s $_[0] } }
sub relative_descriptor {
    my ($path, $base) = @_;
    my $value = descriptor($path);
    $value->{path} = File::Spec->abs2rel($path, $base); $value->{path} =~ tr{\\}{/};
    return $value;
}
sub file_binding { { path => $_[0], sha256 => hash_file($_[0]) } }
sub canonical { JSON::PP->new->canonical->encode($_[0]) }
sub repository {
    my ($path) = @_;
    make_path($path);
    system($real_git, 'init', '-q', $path) == 0 or die 'git init';
    system($real_git, '-C', $path, 'config', 'user.email', 'fixture@example.test') == 0
        or die 'git config';
    system($real_git, '-C', $path, 'config', 'user.name', 'Fixture') == 0
        or die 'git config';
    write_file(File::Spec->catfile($path, 'tracked'), "tracked\n");
    git_commit_files($path, 'initial', 'tracked');
    return abs_path($path);
}
sub git_commit_files {
    my ($repo, $message, @files) = @_;
    system($real_git, '-C', $repo, 'add', '--', @files) == 0 or die 'git add';
    system($real_git, '-C', $repo, 'commit', '-qm', $message) == 0 or die 'git commit';
}
sub head {
    open my $fh, '-|', $real_git, '-C', $_[0], 'rev-parse', 'HEAD' or die 'git head';
    my $value = <$fh>; close $fh or die 'git head'; chomp $value; return $value;
}
sub find_program {
    for my $dir (split /:/, $ENV{PATH} // '') {
        my $path = File::Spec->catfile($dir, $_[0]);
        return abs_path($path) if -f $path && -x $path;
    }
    die "Cannot find $_[0]";
}
sub run_command {
    my @argv = @_;
    pipe my $read, my $write or die "pipe: $!";
    my $pid = fork(); die "fork: $!" unless defined $pid;
    if (!$pid) {
        close $read; open STDOUT, '>&', $write or die $!;
        open STDERR, '>&', $write or die $!;
        exec { $argv[0] } @argv or die "exec: $!";
    }
    close $write; my $output = ''; my $timed_out;
    eval {
        local $SIG{ALRM} = sub { die "timeout\n" }; alarm 120;
        while (1) { my $n = read($read, my $chunk, 8192); die $! unless defined $n;
            last unless $n; $output .= $chunk; die 'output bound' if length($output) > 8 * 1024 * 1024; }
        alarm 0; 1;
    } or $timed_out = 1;
    kill 'KILL', $pid if $timed_out; close $read; waitpid($pid, 0);
    return ($timed_out ? 255 : ($? & 127 ? 128 + ($? & 127) : $? >> 8), $output);
}
sub write_json { write_file($_[0], JSON::PP->new->canonical->pretty->encode($_[1])) }
sub read_json { JSON::PP->new->decode(read_file($_[0])) }
sub hash_file { sha256_hex(read_file($_[0])) }
sub write_file { make_path(dirname($_[0])); open my $fh, '>:raw', $_[0] or die $!;
    print {$fh} $_[1]; close $fh or die $! }
sub read_file { open my $fh, '<:raw', $_[0] or die "Cannot read $_[0]: $!"; local $/; my $v = <$fh>;
    close $fh or die $!; return $v }
