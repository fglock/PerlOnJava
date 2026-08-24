use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = abs_path(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools',
    'prepare_phase36_cpan_launch_manifest.pl');
my $tmp = abs_path(tempdir(CLEANUP => 1));
my ($output_id, $command_id) = (0, 0);
my $real_git = find_program('git');
my $source = repository(File::Spec->catdir($tmp, 'source'));
my $perl5 = repository(File::Spec->catdir($tmp, 'perl5'));
my $jperl = File::Spec->catfile($source, 'jperl');
my $jcpan = File::Spec->catfile($source, 'jcpan');
write_file($jperl, "#!/usr/bin/env perl\ndie qq{jperl must not run\\n};\n");
write_file($jcpan, "#!/usr/bin/env perl\ndie qq{jcpan must not run\\n};\n");
chmod 0755, $jperl, $jcpan or die 'chmod launchers';
git_commit_files($source, 'launchers', 'jperl', 'jcpan');
my $source_commit = head($source);
my $perl5_commit = head($perl5);

my $input = File::Spec->catdir($tmp, 'inputs');
my $corpus_dir = File::Spec->catdir($tmp, 'corpus');
my $authority_dir = File::Spec->catdir($tmp, 'authority');
my $out_dir = File::Spec->catdir($tmp, 'outputs');
make_path($input, $corpus_dir, $authority_dir, $out_dir);
my $jar = File::Spec->catfile($input, 'perlonjava.jar');
my $sbom = File::Spec->catfile($input, 'sbom.json');
my $baseline = File::Spec->catfile($input, 'baseline.log');
write_file($jar, "JAR:$source_commit\n");
write_file($sbom, "{\"bomFormat\":\"CycloneDX\"}\n");
write_file($baseline, "fixture baseline\n");
my $baseline_bytes = read_file($baseline);
my $policy = File::Spec->catfile($root, 'dev', 'tools',
    'phase36_cpan_targets.json');
my $requirements = File::Spec->catfile($input, 'requirements.json');
my $corpus = File::Spec->catfile($corpus_dir, 'manifest.json');
write_requirements();
write_corpus();

my ($package_evidence, $make_evidence) = write_authority();
my $output = next_output('success');
my ($status, $text) = invoke(output => $output);
is($status, 0, 'strict authority bundle succeeds');
is($text, "$output\n", 'strict producer reports the legacy launch path');
my @bundle = bundle_paths($output);
ok(!scalar(grep { !-f $_ || -l $_ } @bundle),
    'legacy launch, bridge, seal, and authority marker are regular files');
my $legacy = read_json($output);
is_deeply([sort keys %$legacy], [qw(identity inputs mode schema_version)],
    'legacy CPAN launch schema remains exact');
my $bridge = read_json("$output.bridge.json");
ok($bridge->{authoritative} && $bridge->{authority_marker_required},
    'strict bridge requires the final authority marker');
is($bridge->{identity}{git_sha256}, hash_file($real_git),
    'bridge binds the explicit trusted Git bytes');
is($bridge->{identity}{baseline_sha256}, hash_file($baseline),
    'bridge binds the selected baseline');
is($bridge->{identity}{package_evidence_sha256}, hash_file($package_evidence),
    'bridge binds strict package evidence');
is($bridge->{identity}{make_evidence_sha256}, hash_file($make_evidence),
    'bridge binds strict make evidence');
my $marker = read_json("$output.authority.json");
is($marker->{bridge}{sha256}, hash_file("$output.bridge.json"),
    'authority marker binds durable bridge bytes');
is($marker->{launch_manifest}{sha256}, hash_file($output),
    'authority marker binds legacy launch bytes');
ok(!scalar(grep { /\.stage\./ } entries($out_dir)),
    'successful durable publication leaves no staging links');

subtest 'PATH substitution is inert with explicit Git authority' => sub {
    my $shim_dir = File::Spec->catdir($tmp, 'malicious-path');
    make_path($shim_dir);
    my $record = File::Spec->catfile($tmp, 'path-shim-ran');
    my $shim = File::Spec->catfile($shim_dir, 'git');
    write_file($shim, "#!/bin/sh\nprintf ran > '$record'\nexit 99\n");
    chmod 0755, $shim or die 'chmod shim';
    local $ENV{PATH} = $shim_dir;
    my $path_output = next_output('path');
    my ($path_status, $path_text) = invoke(output => $path_output);
    is($path_status, 0, 'strict invocation ignores PATH Git shim') or diag $path_text;
    ok(!-e $record, 'PATH Git shim was never invoked');
    ok(-f "$path_output.authority.json", 'strict authority is retained');
};

subtest 'every authority option is duplicate-fenced before input access' => sub {
    for my $name (qw(source-dir requirements cpan-policy package-evidence
            make-evidence git)) {
        my $duplicate_output = next_output("duplicate-$name");
        my ($duplicate_status, $duplicate_text) = invoke(
            output => $duplicate_output,
            extra => ["--$name", authority_argument($name)]);
        isnt($duplicate_status, 0, "duplicate --$name is rejected");
        like($duplicate_text, qr/Duplicate option --\Q$name\E/,
            "duplicate --$name has an exact diagnostic");
        ok(!scalar(grep { -e $_ || -l $_ } bundle_paths($duplicate_output)),
            "duplicate --$name publishes no bundle member");
    }
};

subtest 'package and make cross-identity failures are closed' => sub {
    for my $case (
        [package_source => qr/Package source\/JAR\/SBOM identity differs/],
        [make_jar => qr/Make source\/JAR identity differs/],
        [embedded_commit => qr/Embedded JAR authentication does not bind/],
        [sbom_relation => qr/Package JAR\/SBOM relationship is not strict/],
    ) {
        ($package_evidence, $make_evidence) = write_authority($case->[0]);
        my $bad_output = next_output($case->[0]);
        my ($bad_status, $bad_text) = invoke(output => $bad_output);
        isnt($bad_status, 0, "$case->[0] is rejected");
        like($bad_text, $case->[1], "$case->[0] identifies the failed binding");
        ok(!scalar(grep { -e $_ || -l $_ } bundle_paths($bad_output)),
            "$case->[0] publishes no authority");
    }
    ($package_evidence, $make_evidence) = write_authority();
};

subtest 'durability failpoints roll back every published member' => sub {
    for my $point (qw(after-sidecars-sync after-authority-link
            after-authority-sync)) {
        local $ENV{PHASE36_CPAN_BRIDGE_TEST_FAILPOINT} = $point;
        my $failed_output = next_output("failpoint-$point");
        my ($failed_status, $failed_text) = invoke(output => $failed_output);
        isnt($failed_status, 0, "$point fails closed");
        like($failed_text, qr/failpoint: \Q$point\E/,
            "$point reaches its publication boundary");
        ok(!scalar(grep { -e $_ || -l $_ } bundle_paths($failed_output)),
            "$point rollback leaves no authority or sidecar");
        ok(!scalar(grep { /\.stage\./ } entries($out_dir)),
            "$point rollback removes staging links");
    }
};

subtest 'late mutation after sidecar publication removes authority' => sub {
    my $counter = File::Spec->catfile($tmp, 'late-git-count');
    my $wrapper = File::Spec->catfile($tmp, 'trusted-git-wrapper');
    write_file($wrapper, <<"WRAPPER");
#!/usr/bin/env perl
use strict;
use warnings;
my \$count = 0;
if (open my \$in, '<', '$counter') { \$count = <\$in> // 0; close \$in }
++\$count;
open my \$out, '>', '$counter' or die \$!; print {\$out} "\$count\\n"; close \$out;
if (\$count == 13) {
    open my \$mutate, '>>', '$baseline' or die \$!;
    print {\$mutate} "late mutation\\n";
    close \$mutate;
}
exec '$real_git', \@ARGV or die \$!;
WRAPPER
    chmod 0755, $wrapper or die 'chmod trusted Git wrapper';
    ($package_evidence, $make_evidence) = write_authority(undef, $wrapper);
    my $late_output = next_output('late-mutation');
    my ($late_status, $late_text) = invoke(output => $late_output, git => $wrapper);
    isnt($late_status, 0, 'late mutation rejects publication');
    like($late_text, qr/Selected input changed.*baseline/,
        'late mutation is detected after authority publication');
    ok(!scalar(grep { -e $_ || -l $_ } bundle_paths($late_output)),
        'late mutation rollback leaves no authority, sidecar, or legacy launch');
    write_file($baseline, $baseline_bytes);
    write_requirements();
    write_corpus();
    ($package_evidence, $make_evidence) = write_authority();
};

done_testing();

sub write_authority {
    my ($variant, $git_path) = @_;
    $git_path //= $real_git;
    my $package_root = File::Spec->catdir($authority_dir, 'package');
    make_path(File::Spec->catdir($package_root, 'package', 'logs'));
    my %retained = (
        jar => File::Spec->catfile($package_root, 'package', 'perlonjava.jar'),
        sbom => File::Spec->catfile($package_root, 'package', 'sbom.json'),
        deb => File::Spec->catfile($package_root, 'package', 'perlonjava.deb'),
        java_bom => File::Spec->catfile($package_root, 'package', 'bom.json'),
        perl_bom => File::Spec->catfile($package_root, 'package', 'perl-bom.json'),
        notice => File::Spec->catfile($package_root, 'package', 'notice-license.json'),
        log => File::Spec->catfile($package_root, 'package', 'logs', '001.log'),
        report => File::Spec->catfile($package_root, 'package',
            'package-evidence-report.json'),
    );
    write_file($retained{jar}, read_file($jar));
    write_file($retained{sbom}, read_file($sbom));
    write_file($retained{deb}, "deb\n");
    write_file($retained{java_bom}, "java bom\n");
    write_file($retained{perl_bom}, "perl bom\n");
    write_file($retained{notice}, "notice\n");
    write_file($retained{log}, "log\n");
    my $relation_sbom = $variant && $variant eq 'sbom_relation'
        ? 'f' x 64 : hash_file($retained{sbom});
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
            sbom_sha256 => $relation_sbom,
            relation => 'java-components+joni-fork+perl-components',
            verified => JSON::PP::true },
        trees => {}, notice_license => {}, notice_license_artifact => {},
        retained_artifacts => {},
    };
    write_json($retained{report}, $report);
    my $package_path = File::Spec->catfile($package_root, 'package-evidence.json');
    my $package_source = $variant && $variant eq 'package_source'
        ? 'a' x 40 : $source_commit;
    write_json($package_path, {
        schema_version => 1, kind => 'packaging',
        producer => 'run_phase36_package_evidence.pl', verified => JSON::PP::true,
        identity => { source_commit => $package_source,
            jar_sha256 => hash_file($jar), sbom_sha256 => hash_file($sbom) },
        completion => clean_completion(0),
        artifacts => {
            report => relative_descriptor($retained{report}, $package_root),
            deliverables => { jar => relative_descriptor($retained{jar}, $package_root),
                sbom => relative_descriptor($retained{sbom}, $package_root),
                deb => relative_descriptor($retained{deb}, $package_root) },
            sbom_inputs => {
                java_bom => relative_descriptor($retained{java_bom}, $package_root),
                perl_bom => relative_descriptor($retained{perl_bom}, $package_root) },
            logs => { first => relative_descriptor($retained{log}, $package_root) },
            notice_license => relative_descriptor($retained{notice}, $package_root),
        }, missing_entries => 0, duplicate_entries => 0,
    });

    my $make_root = File::Spec->catdir($authority_dir, 'make');
    make_path($make_root);
    my $embedded = File::Spec->catfile($make_root, 'embedded.json');
    my $embedded_commit = $variant && $variant eq 'embedded_commit'
        ? 'b' x 40 : $source_commit;
    write_json($embedded, { method => 'bounded-direct-content-scan', argv => [],
        archive_tool => descriptor($git_path), capture_sha256 => hash_file($jar),
        capture_size => -s $jar, jar_sha256 => hash_file($jar),
        resolved_commit => $embedded_commit });
    my %make_artifact = (jar => $jar, jar_embedded => $embedded);
    for my $name (qw(jar_version make_log source_after source_before tool_versions)) {
        my $path = File::Spec->catfile($make_root, "$name.txt");
        write_file($path, "$name\n");
        $make_artifact{$name} = $path;
    }
    my %tool_record;
    for my $name (qw(git jar_tool java make perl shell)) {
        my $path = $name eq 'git' ? $git_path : $^X;
        $tool_record{$name} = { %{descriptor($path)}, version_sha256 => '1' x 64 };
    }
    $tool_record{producer} = descriptor($tool);
    my %input_record = map { $_ => descriptor($jperl) } qw(build_gradle
        gradle_wrapper_jar gradle_wrapper_properties gradlew makefile settings_gradle);
    my $make_jar_sha = $variant && $variant eq 'make_jar' ? 'c' x 64 : hash_file($jar);
    my $make_path = File::Spec->catfile($make_root, 'make-evidence.json');
    my $make_doc = {
        schema => 'perlonjava.phase36.make-evidence/v1', schema_version => 1,
        kind => 'make', producer => 'run_phase36_make_evidence.pl',
        mode => 'acceptance', status => 'pass', verified => JSON::PP::true,
        authoritative => JSON::PP::true,
        identity => { source_commit => $source_commit, runner_commit => $source_commit,
            jar_sha256 => $make_jar_sha, jar_reported_commit => $source_commit,
            jar_embedded_commit => $source_commit },
        source => { root => $source,
            before => source_state(), after => source_state() },
        command => { cwd => $source, argv => [$^X], environment => {},
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
    write_file("$make_path.seal", 'SHA-256 ' . $make_doc->{seal}{payload_sha256}
        . ' ' . hash_file($make_path) . "\n");
    return ($package_path, $make_path);
}

sub write_requirements {
    my $policy_sha = hash_file($policy);
    write_json($requirements, {
        schema_version => 1, policy => 'fixture', baseline_sha256 => hash_file($baseline),
        performance_acceptance => {},
        cpan_acceptance => { policy_sha256 => $policy_sha,
            expected_targets => [qw(DBIx::Class DateTime Moo Regexp::Common
                String::Random Template Type::Tiny WWW::Mechanize)],
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
        jvm-comparison.log interpreter-comparison.log
        jvm-strict-regex-comparison.log interpreter-strict-regex-comparison.log
        packaging.log jperl-version.log);
    my %path = map { $_ => File::Spec->catfile($corpus_dir, $_) } @names;
    for my $name (@names) { write_file($path{$name}, "$name\n") }
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
            perl5_commit => $perl5_commit,
            launcher => file_binding($jperl), jar => file_binding($jar),
            sbom => file_binding($sbom), baseline => file_binding($baseline) },
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

sub invoke {
    my %arg = @_;
    my $git = $arg{git} // $real_git;
    my @argv = ($^X, $tool,
        '--source-dir', $source, '--perl5-dir', $perl5,
        '--jperl', $jperl, '--jcpan', $jcpan, '--jar', $jar, '--sbom', $sbom,
        '--baseline', $baseline, '--corpus-manifest', $corpus,
        '--requirements', $requirements, '--cpan-policy', $policy,
        '--package-evidence', $package_evidence, '--make-evidence', $make_evidence,
        '--git', $git, '--output', $arg{output}, @{$arg{extra} // []});
    return run_command(@argv);
}

sub authority_argument {
    my ($name) = @_;
    return { 'source-dir' => $source, requirements => $requirements,
        'cpan-policy' => $policy, 'package-evidence' => $package_evidence,
        'make-evidence' => $make_evidence, git => $real_git }->{$name};
}

sub clean_completion {
    my ($truncated) = @_;
    my $value = { exit_code => 0, signal => 0, timeout => JSON::PP::false,
        incomplete => JSON::PP::false, review_stop => JSON::PP::false };
    $value->{truncated} = JSON::PP::false if $truncated;
    return $value;
}
sub clean_scan { return { classifier => 'fixture', classifier_sha256 => '2' x 64,
    complete_log_sha256 => hash_file($_[0]), count => 0, matches => [] } }
sub source_state { return { all_status_sha256 => '4' x 64,
    diff_sha256 => sha256_hex(''), status_sha256 => sha256_hex(''),
    head => $source_commit, tracked_clean => JSON::PP::true,
    extras => { authority_inputs => [], generated_file_count => 0,
        generated_paths => [], generated_total_bytes => 0 } } }
sub file_binding { return { path => $_[0], sha256 => hash_file($_[0]) } }
sub descriptor { return { path => $_[0], sha256 => hash_file($_[0]), size => -s $_[0] } }
sub relative_descriptor {
    my ($path, $base) = @_;
    my $d = descriptor($path);
    $d->{path} = File::Spec->abs2rel($path, $base);
    $d->{path} =~ tr{\\}{/};
    return $d;
}
sub canonical { JSON::PP->new->canonical->encode($_[0]) }
sub next_output { return File::Spec->catfile($out_dir, $_[0] . '-' . ++$output_id . '.json') }
sub bundle_paths { return ($_[0], "$_[0].bridge.json", "$_[0].bridge.sha256",
    "$_[0].authority.json") }

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
    my $head = <$fh>; close $fh or die 'git head'; chomp $head; return $head;
}
sub find_program {
    my ($name) = @_;
    for my $dir (split /:/, $ENV{PATH} // '') {
        my $path = File::Spec->catfile($dir, $name);
        return abs_path($path) if -f $path && -x $path;
    }
    die "Cannot find $name";
}
sub run_command {
    my @argv = @_;
    my $log = File::Spec->catfile($tmp, 'command-' . ++$command_id . '.log');
    my $pid = fork(); die "fork: $!" unless defined $pid;
    if (!$pid) {
        open STDOUT, '>:raw', $log or die $!;
        open STDERR, '>&', \*STDOUT or die $!;
        exec { $argv[0] } @argv or die "exec: $!";
    }
    waitpid($pid, 0); my $raw = $?;
    return (($raw & 127) ? 128 + ($raw & 127) : $raw >> 8, read_file($log));
}
sub entries { opendir my $dh, $_[0] or die $!; my @e = grep { $_ ne '.' && $_ ne '..' } readdir $dh; closedir $dh; return @e }
sub write_json { write_file($_[0], JSON::PP->new->canonical->pretty->encode($_[1])) }
sub read_json { JSON::PP->new->decode(read_file($_[0])) }
sub hash_file { sha256_hex(read_file($_[0])) }
sub write_file { open my $fh, '>:raw', $_[0] or die $!; print {$fh} $_[1]; close $fh or die $! }
sub read_file { open my $fh, '<:raw', $_[0] or die $!; local $/; my $v = <$fh>; close $fh; return $v }
