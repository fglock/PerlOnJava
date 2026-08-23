#!/usr/bin/env perl

use strict;
use warnings;

use Cwd qw(abs_path getcwd);
use Digest::SHA;
use File::Basename qw(dirname);
use File::Copy qw(copy);
use File::Find;
use File::Path qw(make_path);
use File::Spec;
use FindBin qw($Bin);
use Getopt::Long qw(GetOptions);
use JSON::PP;
use POSIX qw(WNOHANG);
use Time::HiRes qw(sleep time);

use lib File::Spec->catdir($Bin, 'lib');
use PerlOnJava::Phase36PerformanceEvidence qw(
    evaluate_performance load_json policy_sha256 seal_authority
);

my %option = (
    psycho_timeout => 600,
    ordered_timeout => 900,
    ordinary_timeout => 300,
    time_style => $^O eq 'darwin' ? 'mac' : 'gnu',
    ordered_test => File::Spec->catfile('t', '87ordered.t'),
);
my $help;
my @review_explanation;
GetOptions(
    'baseline-source=s' => \$option{baseline_source},
    'candidate-source=s' => \$option{candidate_source},
    'perl5-source=s' => \$option{perl5_source},
    'baseline-jar=s' => \$option{baseline_jar},
    'candidate-jar=s' => \$option{candidate_jar},
    'baseline-launcher=s' => \$option{baseline_launcher},
    'candidate-launcher=s' => \$option{candidate_launcher},
    'interpreter-launcher=s' => \$option{interpreter_launcher},
    'java=s' => \$option{java},
    'perl=s' => \$option{perl},
    'jfr-tool=s' => \$option{jfr_tool},
    'jfc=s' => \$option{jfc},
    'time=s' => \$option{time_executable},
    'time-style=s' => \$option{time_style},
    'ordered-fixture-template=s' => \$option{ordered_fixture_template},
    'ordered-fixture-manifest=s' => \$option{ordered_fixture_manifest},
    'dbix-archive=s' => \$option{dbix_archive},
    'ordered-test=s' => \$option{ordered_test},
    'authority-key=s' => \$option{authority_key},
    'output-root=s' => \$option{output_root},
    'requirements=s' => \$option{requirements},
    'psycho-timeout=s' => \$option{psycho_timeout},
    'ordered-timeout=s' => \$option{ordered_timeout},
    'ordinary-timeout=s' => \$option{ordinary_timeout},
    'review-explanation=s@' => \@review_explanation,
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;
$option{requirements} //= File::Spec->catfile($Bin,
    'phase36_acceptance_requirements.json');
for my $field (qw(baseline_source candidate_source perl5_source baseline_jar
        candidate_jar baseline_launcher candidate_launcher interpreter_launcher
        java perl jfr_tool jfc time_executable ordered_fixture_template
        ordered_fixture_manifest dbix_archive authority_key output_root
        requirements)) {
    die "--" . ($field =~ s/_/-/gr) . " is required\n"
        unless defined($option{$field}) && length($option{$field});
}
die "--time-style must be mac or gnu\n"
    unless $option{time_style} =~ /\A(?:mac|gnu)\z/;
for my $field (qw(psycho_timeout ordered_timeout ordinary_timeout)) {
    die "$field must be between 1 and 7200 seconds\n"
        unless bounded_cli_integer($option{$field}, 1, 7200);
}

my @forbidden_environment = qw(
    JPERL_OPTS JPERL_UNIMPLEMENTED PERL_SKIP_PSYCHO_TEST PERL_SKIP_BIG_MEM_TESTS
    JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JAVA_HOME CLASSPATH
    PERL5OPT PERL5LIB PERLLIB PERL_LOCAL_LIB_ROOT PERL_MB_OPT PERL_MM_OPT
    GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE PHASE36_SOURCE_COMMIT
    PHASE36_JAR_SHA256 PHASE36_PERFORMANCE_SIDE
);
my @ambient = grep { exists $ENV{$_} } @forbidden_environment;
die "ambient execution-injection variables are forbidden: @ambient\n" if @ambient;

my $root = private_empty_directory($option{output_root});
my $sealed_dir = File::Spec->catdir($root, 'sealed');
make_path($sealed_dir, { mode => 0700 });
my $environment_home = File::Spec->catdir($root, 'environment-home');
my $environment_tmp = File::Spec->catdir($root, 'environment-tmp');
make_path($environment_home, $environment_tmp, { mode => 0700 });
my %base_child_environment = (
    PATH => defined($ENV{PATH}) && length($ENV{PATH})
        ? $ENV{PATH} : '/usr/bin:/bin',
    LANG => 'C', LC_ALL => 'C', TZ => 'UTC',
    HOME => $environment_home, PERLONJAVA_HOME => $environment_home,
    TMPDIR => $environment_tmp,
    PERLONJAVA_JAVA_BIN => abs_path($option{java}),
);
$base_child_environment{SystemRoot} = $ENV{SystemRoot}
    if $^O eq 'MSWin32' && defined($ENV{SystemRoot});
my $rules = load_json($option{requirements}, 'performance requirements',
    4 * 1024 * 1024);
my $requirements_initial_sha256 = sha256_file($option{requirements});
my $authority_key_initial_sha256 = sha256_file($option{authority_key});
die "--perl does not identify the interpreter executing this producer\n"
    unless sha256_file(authority_file($option{perl}, 'Perl interpreter', 1))
        eq sha256_file(authority_file($^X, 'executing Perl interpreter', 1));
my $source = source_state(\%option);
my %identity = (%$source);
my %trusted_path = (
    benchmark => File::Spec->catfile($Bin, 'phase36_regex_benchmark.pl'),
    ordinary_performance_producer => File::Spec->catfile($Bin,
        'run_phase36_regex_performance.pl'),
    performance_evaluator => File::Spec->catfile($Bin, 'lib', 'PerlOnJava',
        'Phase36PerformanceEvidence.pm'),
    perl_interpreter => $option{perl},
    jfr_metrics_producer => File::Spec->catfile($Bin, 'Phase36JfrMetrics.java'),
    jfc => $option{jfc},
    jdk_executable => $option{java},
    jdk_version_log => File::Spec->catfile($sealed_dir, 'jdk-version.log'),
    baseline_jar => $option{baseline_jar},
    candidate_jar => $option{candidate_jar},
    baseline_launcher => $option{baseline_launcher},
    candidate_launcher => $option{candidate_launcher},
    interpreter_launcher => $option{interpreter_launcher},
    jfr_tool => $option{jfr_tool},
    time_executable => $option{time_executable},
    ordered_test_source => File::Spec->catfile($option{ordered_fixture_template},
        File::Spec->splitdir($option{ordered_test})),
    ordered_fixture_manifest => $option{ordered_fixture_manifest},
    dbix_archive => $option{dbix_archive},
);
for my $field (sort keys %trusted_path) {
    next if $field eq 'jdk_version_log';
    my $source_path = authority_file($trusted_path{$field}, $field,
        $field =~ /(?:launcher|executable|jfr_tool|time_executable)/);
    my $name = $field eq 'jfr_metrics_producer' ? 'Phase36JfrMetrics.java'
        : $field eq 'benchmark' ? 'phase36_regex_benchmark.pl'
        : $field eq 'ordinary_performance_producer'
            ? 'run_phase36_regex_performance.pl'
        : $field eq 'performance_evaluator' ? 'Phase36PerformanceEvidence.pm'
        : $field eq 'perl_interpreter' ? 'perl'
        : $field;
    my $snapshot = File::Spec->catfile($sealed_dir, $name);
    copy($source_path, $snapshot) or die "Cannot snapshot $field: $!\n";
    chmod(($field =~ /(?:launcher|executable|jfr_tool|time_executable|perl_interpreter)/)
        ? 0700 : 0600, $snapshot) or die "Cannot protect $snapshot: $!\n";
    $identity{$field} = artifact($root, $snapshot);
}
my %sealed = map {
    $_ => File::Spec->catfile($root,
        File::Spec->splitdir($identity{$_}{path}))
} grep { $_ ne 'jdk_version_log' } keys %trusted_path;
capture_command($trusted_path{jdk_version_log}, 30, undef, {},
    [$option{java}, '-version'],
    [[$option{java}, $identity{jdk_executable}{sha256}]]);
$identity{jdk_version_log} = artifact($root, $trusted_path{jdk_version_log});
my $environment_contract_path = File::Spec->catfile($sealed_dir,
    'execution-environment.json');
write_json_atomic($environment_contract_path, {
    schema_version => 1, complete => JSON::PP::true,
    inheritance_allowlist => [qw(PATH)],
    forbidden_ambient => \@forbidden_environment,
    base_effective_environment => \%base_child_environment,
    lane_overrides => {
        ordinary => [qw(PERLONJAVA_JAR PHASE36_SOURCE_COMMIT
            PHASE36_JAR_SHA256 PHASE36_PERFORMANCE_SIDE)],
        psycho_speed => [qw(PERLONJAVA_JAR)],
        ordered => [qw(PERLONJAVA_JAR HOME PERLONJAVA_HOME TMPDIR JPERL_OPTS)],
    },
}, 0);
$identity{execution_environment} = artifact($root, $environment_contract_path);
my $sealed_ordered_fixture = File::Spec->catdir($root,
    'sealed-ordered-fixture');
copy_tree($option{ordered_fixture_template}, $sealed_ordered_fixture);
my $fixture_tree_manifest_path = File::Spec->catfile($sealed_dir,
    'ordered-fixture-tree.json');
write_json_atomic($fixture_tree_manifest_path,
    fixture_tree_manifest($sealed_ordered_fixture), 0);
$identity{ordered_fixture_tree_manifest} = artifact($root,
    $fixture_tree_manifest_path);
my @sealed_review_explanations;
my %review_metric;
for my $spec (@review_explanation) {
    die "--review-explanation must be METRIC=FILE\n"
        unless $spec =~ /\A(peak_committed_heap_bytes|max_rss_bytes)=(.+)\z/;
    my ($metric, $path) = ($1, $2);
    die "duplicate --review-explanation for $metric\n" if $review_metric{$metric}++;
    my $source_path = authority_file($path, "$metric review explanation", 0);
    my $review_dir = File::Spec->catdir($root, 'review');
    make_path($review_dir, { mode => 0700 });
    my $snapshot = File::Spec->catfile($review_dir, "$metric.md");
    copy($source_path, $snapshot) or die "Cannot snapshot $metric explanation: $!\n";
    chmod 0600, $snapshot;
    push @sealed_review_explanations,
        { metric => $metric, artifact => artifact($root, $snapshot) };
}

my $ordinary_dir = File::Spec->catdir($root, 'ordinary');
make_path($ordinary_dir, { mode => 0700 });
my $ordinary_output = File::Spec->catfile($ordinary_dir, 'performance.json');
my $ordinary_producer_log = File::Spec->catfile($root, 'ordinary-producer.log');
run_bounded('ordinary five-pair producer', 4200, undef, {},
    $ordinary_producer_log, [
        $option{perl}, $sealed{ordinary_performance_producer},
        '--baseline-source', $option{baseline_source},
        '--candidate-source', $option{candidate_source},
        '--baseline-jar', $sealed{baseline_jar},
        '--candidate-jar', $sealed{candidate_jar},
        '--baseline-launcher', $option{baseline_launcher},
        '--candidate-launcher', $option{candidate_launcher},
        '--benchmark', $sealed{benchmark},
        '--java', $option{java},
        '--evidence-dir', $ordinary_dir,
        '--output', $ordinary_output,
        '--samples', '5', '--timeout', "$option{ordinary_timeout}",
    ], [[$option{perl}, $identity{perl_interpreter}{sha256}],
        [$option{baseline_launcher}, $identity{baseline_launcher}{sha256}],
        [$option{candidate_launcher}, $identity{candidate_launcher}{sha256}],
        [$option{java}, $identity{jdk_executable}{sha256}]]);
my $ordinary = load_json($ordinary_output, 'ordinary producer output',
    8 * 1024 * 1024);
for my $mapping (['benchmark' => 'benchmark'], ['baseline_jar' => 'baseline_jar'],
        ['candidate_jar' => 'candidate_jar'],
        ['baseline_launcher' => 'baseline_launcher'],
        ['candidate_launcher' => 'candidate_launcher'],
        ['java' => 'jdk_executable']) {
    $ordinary->{artifacts}{$mapping->[0]} = $identity{$mapping->[1]};
}
rewrite_artifact_paths($ordinary, $root);
write_json_atomic($ordinary_output, $ordinary, 1);

my @psycho_rows;
for my $backend (qw(jvm interpreter)) {
    my $launcher = $backend eq 'jvm'
        ? $option{candidate_launcher} : $option{interpreter_launcher};
    my $launcher_identity = $backend eq 'jvm'
        ? $identity{candidate_launcher} : $identity{interpreter_launcher};
    for my $test (qw(re/pat_psycho.t re/pat_psycho_thr.t re/speed.t re/speed_thr.t)) {
        my $source_path = File::Spec->catfile($option{candidate_source},
            'perl5_t', 't', File::Spec->splitdir($test));
        authority_file($source_path, $test, 0);
        (my $slug = "$backend-$test") =~ s{[^A-Za-z0-9]+}{-}g;
        my $snapshot = File::Spec->catfile($sealed_dir, "$slug.t");
        copy($source_path, $snapshot) or die "Cannot snapshot $test: $!\n";
        chmod 0600, $snapshot;
        my $tap = File::Spec->catfile($root, 'psycho-speed', "$slug.tap");
        my $command_path = File::Spec->catfile($root, 'psycho-speed',
            "$slug-command.json");
        make_path(dirname($tap), { mode => 0700 });
        my $command = {
            schema_version => 1, authority_selected => JSON::PP::true,
            argv => [$launcher, $snapshot],
            timeout_seconds => 0 + $option{psycho_timeout},
            source_commit => $identity{candidate_source_commit},
            jar_sha256 => $identity{candidate_jar}{sha256},
            launcher_sha256 => $launcher_identity->{sha256},
            test_source_sha256 => sha256_file($source_path),
            environment_contract_sha256 => $identity{execution_environment}{sha256},
        };
        write_json_atomic($command_path, $command, 0);
        my %environment = (PERLONJAVA_JAR => $sealed{candidate_jar});
        run_bounded("$backend $test", $option{psycho_timeout},
            $option{candidate_source}, \%environment, $tap,
            [$launcher, $snapshot],
            [[$launcher, $launcher_identity->{sha256}],
                [$option{java}, $identity{jdk_executable}{sha256}]]);
        push @psycho_rows, {
            backend => $backend, test => $test,
            source_commit => $identity{candidate_source_commit},
            jar_sha256 => $identity{candidate_jar}{sha256},
            launcher_sha256 => $launcher_identity->{sha256},
            exit_code => 0, timeout => JSON::PP::false,
            truncated => JSON::PP::false,
            test_source => artifact($root, $snapshot),
            tap => artifact($root, $tap),
            command => artifact($root, $command_path),
        };
    }
}

my @ordered_runs;
my @order = qw(baseline candidate candidate baseline);
for my $index (0 .. $#order) {
    my $side = $order[$index];
    my $launcher = $option{"${side}_launcher"};
    my $jar = $sealed{"${side}_jar"};
    my $run_dir = File::Spec->catdir($root, 'ordered-state', "$index-$side");
    copy_tree($sealed_ordered_fixture, $run_dir);
    my $run_test = File::Spec->catfile($run_dir,
        File::Spec->splitdir($option{ordered_test}));
    die "copied 87ordered source differs from authority-selected input\n"
        unless -f $run_test && sha256_file($run_test) eq
            $identity{ordered_test_source}{sha256};
    my $logs = File::Spec->catdir($root, 'ordered', "$index-$side");
    make_path($logs, { mode => 0700 });
    my $before = File::Spec->catfile($logs, 'process-before.log');
    my $after = File::Spec->catfile($logs, 'process-after.log');
    my $load_before = File::Spec->catfile($logs, 'load-before.log');
    my $load_after = File::Spec->catfile($logs, 'load-after.log');
    capture_inventory($before);
    capture_load($load_before);
    assert_no_competing_work($before);
    my $started_at = utc_timestamp();
    my $tap = File::Spec->catfile($logs, '87ordered.tap');
    my $time_raw = File::Spec->catfile($logs, 'time.raw');
    my $jfr = File::Spec->catfile($logs, '87ordered.jfr');
    my $jfr_summary = File::Spec->catfile($logs, 'jfr-summary.txt');
    my $command_path = File::Spec->catfile($logs, 'command.json');
    my $environment_path = File::Spec->catfile($logs, 'environment.json');
    my $admission_path = File::Spec->catfile($logs, 'load-admission.json');
    my $metrics_path = File::Spec->catfile($logs, 'jfr-metrics.json');
    my $home = File::Spec->catdir($run_dir, '.phase36-home');
    my $tmp = File::Spec->catdir($run_dir, '.phase36-tmp');
    make_path($home, $tmp, { mode => 0700 });
    my $jfr_option = "-XX:NativeMemoryTracking=summary "
        . "-XX:StartFlightRecording=filename=$jfr,settings="
        . $sealed{jfc}
        . ",dumponexit=true,path-to-gc-roots=true,maxsize=2g";
    my %run_environment = (
        PERLONJAVA_JAR => $jar, HOME => $home,
        PERLONJAVA_HOME => $home, TMPDIR => $tmp, JPERL_OPTS => $jfr_option,
    );
    my $environment = {
        schema_version => 1, complete => JSON::PP::true,
        unset => \@forbidden_environment,
        private_roots => { HOME => $home, PERLONJAVA_HOME => $home, TMPDIR => $tmp },
        base_environment_sha256 => $identity{execution_environment}{sha256},
        effective_environment => {
            %base_child_environment, %run_environment,
        },
    };
    write_json_atomic($environment_path, $environment, 0);
    my @time_argv = ($option{time_executable},
        $option{time_style} eq 'mac' ? ('-lp') : ('-v'),
        '-o', $time_raw, $launcher, $option{ordered_test});
    my $command = {
        schema_version => 1, authority_selected => JSON::PP::true,
        argv => \@time_argv,
        timeout_seconds => 0 + $option{ordered_timeout},
        source_commit => $identity{"${side}_source_commit"},
        jar_sha256 => $identity{"${side}_jar"}{sha256},
        launcher_sha256 => $identity{"${side}_launcher"}{sha256},
        jdk_executable_sha256 => $identity{jdk_executable}{sha256},
        jdk_version_log_sha256 => $identity{jdk_version_log}{sha256},
        jfc_sha256 => $identity{jfc}{sha256},
        jfr_tool_sha256 => $identity{jfr_tool}{sha256},
        jfr_metrics_producer_sha256 => $identity{jfr_metrics_producer}{sha256},
        time_executable_sha256 => $identity{time_executable}{sha256},
        ordered_test_source_sha256 => $identity{ordered_test_source}{sha256},
        ordered_fixture_manifest_sha256 => $identity{ordered_fixture_manifest}{sha256},
        ordered_fixture_tree_manifest_sha256 =>
            $identity{ordered_fixture_tree_manifest}{sha256},
        dbix_archive_sha256 => $identity{dbix_archive}{sha256},
        environment_sha256 => artifact($root, $environment_path)->{sha256},
        perl5_commit => $identity{perl5_commit},
        jfr_max_bytes => 2 * 1024 * 1024 * 1024,
        unset_environment => \@forbidden_environment,
    };
    write_json_atomic($command_path, $command, 0);
    run_bounded("87ordered $index $side", $option{ordered_timeout}, $run_dir,
        \%run_environment, $tap, \@time_argv,
        [[$option{time_executable}, $identity{time_executable}{sha256}],
            [$launcher, $identity{"${side}_launcher"}{sha256}],
            [$option{java}, $identity{jdk_executable}{sha256}]]);
    die "87ordered source mutated during execution\n"
        unless -f $run_test && sha256_file($run_test) eq
            $identity{ordered_test_source}{sha256};
    capture_command($jfr_summary, 120, undef, {},
        [$option{jfr_tool}, 'summary', $jfr],
        [[$option{jfr_tool}, $identity{jfr_tool}{sha256}]]);
    capture_inventory($after);
    capture_load($load_after);
    assert_no_competing_work($after);
    my $finished_at = utc_timestamp();
    my $admission = {
        schema_version => 1, complete => JSON::PP::true,
        process_inventory_before_sha256 => sha256_file($before),
        process_inventory_after_sha256 => sha256_file($after),
        load_before_sha256 => sha256_file($load_before),
        load_after_sha256 => sha256_file($load_after),
        load_average_before => load_number($load_before),
        load_average_after => load_number($load_after),
        started_at => $started_at, finished_at => $finished_at,
        active_expensive_owners_before => ['phase36-performance'],
        active_expensive_owners_after => ['phase36-performance'],
        unexpected_perlonjava_jvms => [],
    };
    write_json_atomic($admission_path, $admission, 0);
    my $run = {
        side => $side,
        source_commit => $identity{"${side}_source_commit"},
        jar_sha256 => $identity{"${side}_jar"}{sha256},
        launcher_sha256 => $identity{"${side}_launcher"}{sha256},
        jdk_executable_sha256 => $identity{jdk_executable}{sha256},
        jdk_version_log_sha256 => $identity{jdk_version_log}{sha256},
        jfc_sha256 => $identity{jfc}{sha256},
        exit_code => 0, timeout => JSON::PP::false,
        timeout_seconds => 0 + $option{ordered_timeout},
        command => artifact($root, $command_path),
        environment => artifact($root, $environment_path),
        process_inventory_before => artifact($root, $before),
        process_inventory_after => artifact($root, $after),
        load_before => artifact($root, $load_before),
        load_after => artifact($root, $load_after),
        load_admission => artifact($root, $admission_path),
        tap => artifact($root, $tap), time_raw => artifact($root, $time_raw),
        jfr_recording => artifact($root, $jfr),
        jfr_summary => artifact($root, $jfr_summary),
    };
    capture_command($metrics_path, 120, undef, {}, [
        $option{java}, $sealed{jfr_metrics_producer},
        '--recording', $jfr, '--command', $command_path,
        '--jfr-tool', $option{jfr_tool},
        '--jdk-executable', $option{java},
        '--jdk-version-log', $trusted_path{jdk_version_log},
        '--jfc', $sealed{jfc}, '--helper', $sealed{jfr_metrics_producer},
    ], [[$option{java}, $identity{jdk_executable}{sha256}],
        [$option{jfr_tool}, $identity{jfr_tool}{sha256}]]);
    $run->{jfr_metrics} = artifact($root, $metrics_path);
    push @ordered_runs, $run;
}

my $document = {
    schema_version => 1, kind => 'phase36-final-performance',
    identity => \%identity,
    ordinary => { artifact => artifact($root, $ordinary_output) },
    psycho_speed => { rows => \@psycho_rows },
    ordered => { runs => \@ordered_runs },
    review_explanations => \@sealed_review_explanations,
};
die "performance requirements changed during execution\n"
    unless sha256_file($option{requirements}) eq $requirements_initial_sha256;
die "authority key changed during execution\n"
    unless sha256_file($option{authority_key}) eq $authority_key_initial_sha256;
$document->{authority} = seal_authority($document, $rules, {
    authority_key => $option{authority_key},
    baseline_source => $option{baseline_source},
    candidate_source => $option{candidate_source},
    perl5_source => $option{perl5_source},
    orchestrator => File::Spec->catfile($Bin, 'run_phase36_final_performance.pl'),
    ordinary_performance_producer => File::Spec->catfile($Bin,
        'run_phase36_regex_performance.pl'),
    performance_evaluator => File::Spec->catfile($Bin, 'lib', 'PerlOnJava',
        'Phase36PerformanceEvidence.pm'),
    benchmark => $trusted_path{benchmark},
    perl => $option{perl},
    jfr_metrics_producer => File::Spec->catfile($Bin, 'Phase36JfrMetrics.java'),
    requirements => $option{requirements},
});
my $draft = File::Spec->catfile($root, 'draft.json');
write_json_atomic($draft, $document, 0);
my $evaluation = evaluate_performance($document, $rules, $root, {
    java => $option{java}, perl => $option{perl},
    authority_key => $option{authority_key},
    baseline_source => $option{baseline_source},
    candidate_source => $option{candidate_source},
    perl5_source => $option{perl5_source},
    orchestrator => File::Spec->catfile($Bin,
        'run_phase36_final_performance.pl'),
    ordinary_performance_producer => File::Spec->catfile($Bin,
        'run_phase36_regex_performance.pl'),
    benchmark => $trusted_path{benchmark},
    jfr_metrics_producer => File::Spec->catfile($Bin,
        'Phase36JfrMetrics.java'),
    requirements => $option{requirements},
});
$document->{policy_sha256} = policy_sha256($rules);
$document->{evaluation} = $evaluation;
$document->{decision} = $evaluation->{decision};
$document->{verified} = $evaluation->{verified};
my $final = File::Spec->catfile($root, 'final.json');
write_json_atomic($final, $document, 0);
print "$final\n";
exit($evaluation->{decision} eq 'passed' ? 0
    : $evaluation->{decision} eq 'review-stop' ? 2 : 1);

sub source_state {
    my ($opt) = @_;
    my $baseline = git_line($opt->{baseline_source}, qw(rev-parse HEAD));
    my $candidate = git_line($opt->{candidate_source}, qw(rev-parse HEAD));
    my $parent = git_line($opt->{candidate_source}, qw(rev-parse HEAD^));
    my $perl5 = git_line($opt->{perl5_source}, qw(rev-parse HEAD));
    die "candidate is not the direct child of baseline\n" unless $parent eq $baseline;
    for my $path ($opt->{baseline_source}, $opt->{candidate_source},
            $opt->{perl5_source}) {
        die "authority-selected source is dirty: $path\n"
            if length(git_output($path, qw(status --porcelain --untracked-files=all)));
    }
    return {
        baseline_source_commit => $baseline,
        candidate_source_commit => $candidate,
        candidate_parent_commit => $parent,
        perl5_commit => $perl5,
    };
}

sub run_bounded {
    my ($label, $timeout, $cwd, $environment, $log, $argv, $verified) = @_;
    verify_identities($label, $verified);
    make_path(dirname($log), { mode => 0700 }) unless -d dirname($log);
    die "Refusing to overwrite $log\n" if -e $log;
    my $pid = fork();
    die "Cannot fork $label: $!\n" unless defined $pid;
    if ($pid == 0) {
        eval { POSIX::setpgid(0, 0) };
        chdir $cwd or die "Cannot chdir $cwd: $!\n" if defined $cwd;
        open STDOUT, '>:raw', $log or die "Cannot create $log: $!\n";
        open STDERR, '>&', STDOUT or die "Cannot redirect $log: $!\n";
        %ENV = (%base_child_environment, %$environment);
        if (!exec { $argv->[0] } @$argv) {
            POSIX::_exit(127);
        }
    }
    eval { POSIX::setpgid($pid, $pid) };
    my $process_group = eval { getpgrp($pid) };
    my $has_process_group = defined($process_group) && $process_group == $pid;
    my $deadline = time() + $timeout;
    while (1) {
        my $waited = waitpid($pid, WNOHANG);
        if ($waited == $pid) {
            my $status = $?;
            verify_identities($label, $verified);
            die "$label failed with status $status; raw log $log\n" if $status != 0;
            return;
        }
        die "waitpid failed for $label: $!\n" if $waited == -1;
        if (time() >= $deadline) {
            my $target = $has_process_group ? -$pid : $pid;
            kill 'TERM', $target;
            sleep 0.2;
            my $reaped = waitpid($pid, WNOHANG);
            if ($reaped == 0) {
                kill 'KILL', $target;
                waitpid($pid, 0);
            }
            verify_identities($label, $verified);
            die "$label timed out after ${timeout}s; raw log $log\n";
        }
        sleep 0.02;
    }
}

sub capture_command {
    my ($output, $timeout, $cwd, $environment, $argv, $verified) = @_;
    run_bounded(join(' ', @$argv), $timeout, $cwd, $environment, $output, $argv,
        $verified);
}

sub verify_identities {
    my ($label, $verified) = @_;
    return unless ref($verified) eq 'ARRAY';
    for my $entry (@$verified) {
        my ($path, $expected) = @$entry;
        die "$label authority-selected executable disappeared: $path\n"
            unless -f $path;
        die "$label authority-selected executable identity changed: $path\n"
            unless sha256_file($path) eq $expected;
    }
}

sub capture_inventory {
    my ($output) = @_;
    capture_command($output, 30, undef, {},
        ['ps', '-eo', 'pid=,ppid=,etime=,pcpu=,pmem=,args=']);
}

sub capture_load {
    my ($output) = @_;
    capture_command($output, 30, undef, {}, ['uptime']);
}

sub assert_no_competing_work {
    my ($inventory) = @_;
    open my $fh, '<:raw', $inventory or die $!;
    while (my $line = <$fh>) {
        next if $line =~ /run_phase36_final_performance/;
        die "competing build or PerlOnJava JVM blocks timing admission: $line"
            if $line =~ /(?:GradleWorker|GradleDaemon|\bmake\b|\bjperl\b|\bjcpan\b|perlonjava-.*\.jar)/i;
    }
    close $fh;
}

sub load_number {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die $!;
    my $text = do { local $/; <$fh> };
    close $fh;
    return 0 + $1 if $text =~ /load averages?:\s*([0-9]+(?:\.[0-9]+)?)/i;
    return 0 + $1 if $text =~ /load average:\s*([0-9]+(?:\.[0-9]+)?)/i;
    die "Cannot parse load average from $path\n";
}

sub copy_tree {
    my ($source, $destination) = @_;
    die "Fixture destination already exists: $destination\n" if -e $destination;
    make_path($destination, { mode => 0700 });
    my $base = abs_path($source) or die "Cannot resolve fixture $source\n";
    find({ no_chdir => 1, wanted => sub {
        return if $File::Find::name eq $base;
        my $relative = File::Spec->abs2rel($File::Find::name, $base);
        my $target = File::Spec->catfile($destination,
            File::Spec->splitdir($relative));
        if (-l $File::Find::name) {
            my $link = readlink($File::Find::name);
            symlink $link, $target or die "Cannot copy symlink $target: $!\n";
        } elsif (-d _) {
            make_path($target, { mode => 0700 });
        } elsif (-f _) {
            copy($File::Find::name, $target) or die "Cannot copy $target: $!\n";
            chmod((stat($File::Find::name))[2] & 0777, $target);
        } else {
            die "Unsupported fixture entry $File::Find::name\n";
        }
    }}, $base);
}

sub fixture_tree_manifest {
    my ($directory) = @_;
    my $base = abs_path($directory) or die "Cannot resolve fixture $directory\n";
    my @entry;
    find({ no_chdir => 1, wanted => sub {
        return if $File::Find::name eq $base;
        die "ordered fixture exceeds 100000 entries\n" if @entry >= 100_000;
        my $relative = File::Spec->abs2rel($File::Find::name, $base);
        my $mode = sprintf('%04o', (lstat($File::Find::name))[2] & 0777);
        if (-l $File::Find::name) {
            push @entry, { path => $relative, type => 'symlink', mode => $mode,
                target => readlink($File::Find::name) };
        } elsif (-d _) {
            push @entry, { path => $relative, type => 'directory', mode => $mode };
        } elsif (-f _) {
            push @entry, { path => $relative, type => 'file', mode => $mode,
                size => 0 + (-s $File::Find::name),
                sha256 => sha256_file($File::Find::name) };
        } else {
            die "unsupported ordered fixture entry $File::Find::name\n";
        }
    }}, $base);
    return { schema_version => 1, complete => JSON::PP::true,
        entries => [sort { $a->{path} cmp $b->{path} } @entry] };
}

sub rewrite_artifact_paths {
    my ($value, $root_dir) = @_;
    if (ref($value) eq 'HASH') {
        if (exists($value->{path}) && exists($value->{sha256})) {
            my $candidate = File::Spec->file_name_is_absolute($value->{path})
                ? $value->{path}
                : File::Spec->catfile($root_dir,
                    File::Spec->splitdir($value->{path}));
            my $absolute = abs_path($candidate);
            die "Ordinary artifact escapes final evidence root\n"
                unless $absolute && path_inside($absolute, $root_dir);
            $value->{path} = File::Spec->abs2rel($absolute, $root_dir);
        } else {
            rewrite_artifact_paths($_, $root_dir) for values %$value;
        }
    } elsif (ref($value) eq 'ARRAY') {
        rewrite_artifact_paths($_, $root_dir) for @$value;
    }
}

sub artifact {
    my ($root_dir, $path) = @_;
    my $absolute = abs_path($path) or die "Cannot resolve artifact $path\n";
    die "Artifact escapes evidence root: $path\n"
        unless path_inside($absolute, $root_dir);
    return { path => File::Spec->abs2rel($absolute, $root_dir),
        sha256 => sha256_file($absolute), size => 0 + (-s $absolute) };
}

sub authority_file {
    my ($path, $label, $executable) = @_;
    my $absolute = abs_path($path) or die "Cannot resolve $label $path\n";
    die "$label is not a regular file\n" unless -f $absolute;
    die "$label is not executable\n" if $executable && !-x $absolute;
    return $absolute;
}

sub private_empty_directory {
    my ($path) = @_;
    my $absolute = abs_path($path) or die "Cannot resolve output root $path\n";
    die "Output root must be a directory\n" unless -d $absolute;
    opendir my $dh, $absolute or die $!;
    my @entry = grep { $_ ne '.' && $_ ne '..' } readdir $dh;
    closedir $dh;
    die "Output root must be empty\n" if @entry;
    my $mode = (stat $absolute)[2] & 0777;
    die sprintf("Output root must be private, mode is %04o\n", $mode)
        if $mode & 0077;
    return $absolute;
}

sub write_json_atomic {
    my ($path, $value, $replace) = @_;
    my $temporary = "$path.tmp.$$";
    die "Refusing to overwrite $path\n" if !$replace && -e $path;
    open my $fh, '>:raw', $temporary or die "Cannot create $temporary: $!\n";
    chmod 0600, $temporary;
    print {$fh} JSON::PP->new->canonical->pretty->encode($value) or die $!;
    close $fh or die $!;
    if ($replace) {
        rename $temporary, $path or die "Cannot replace $path: $!\n";
    } else {
        link $temporary, $path or die "Cannot publish $path: $!\n";
        unlink $temporary or die "Cannot remove $temporary: $!\n";
    }
}

sub git_line {
    my ($directory, @args) = @_;
    my $output = git_output($directory, @args);
    $output =~ s/\s+\z//;
    die "Malformed Git identity in $directory\n"
        unless $output =~ /\A[0-9a-f]{40}\z/;
    return $output;
}

sub git_output {
    my ($directory, @args) = @_;
    local %ENV = %base_child_environment;
    open my $fh, '-|', 'git', '-C', $directory, @args
        or die "Cannot execute Git in $directory: $!\n";
    my $output = do { local $/; <$fh> };
    close $fh or die "Git command failed in $directory\n";
    return $output;
}

sub sha256_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die $!;
    my $hash = Digest::SHA->new(256)->addfile($fh)->hexdigest;
    close $fh or die $!;
    return $hash;
}

sub path_inside {
    my ($path, $root_dir) = @_;
    my $relative = File::Spec->abs2rel($path, $root_dir);
    return $relative ne File::Spec->updir
        && $relative !~ m{^\.\.(?:[\\/]|\z)};
}

sub utc_timestamp {
    my @time = gmtime;
    return sprintf('%04d-%02d-%02dT%02d:%02d:%02dZ',
        $time[5] + 1900, $time[4] + 1, @time[3, 2, 1, 0]);
}

sub bounded_cli_integer {
    my ($value, $minimum, $maximum) = @_;
    return 0 unless defined($value) && !ref($value)
        && "$value" =~ /\A\d{1,4}\z/;
    return $value >= $minimum && $value <= $maximum;
}

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: run_phase36_final_performance.pl
  --baseline-source DIR --candidate-source DIR --perl5-source DIR
  --baseline-jar FILE --candidate-jar FILE
  --baseline-launcher FILE --candidate-launcher FILE
  --interpreter-launcher FILE --java FILE --perl FILE --jfr-tool FILE --jfc FILE
  --time FILE --time-style mac|gnu
  --ordered-fixture-template DIR --ordered-fixture-manifest FILE
  --dbix-archive FILE [--ordered-test t/87ordered.t]
  --authority-key PRIVATE_KEY --output-root PRIVATE_EMPTY_DIR
  [--requirements FILE] [--psycho-timeout N] [--ordered-timeout N]
  [--ordinary-timeout N]
  [--review-explanation peak_committed_heap_bytes=FILE]
  [--review-explanation max_rss_bytes=FILE]

This is the only authoritative Phase 36 performance producer. It verifies the
actual clean Git relation, selects every workload/input from CLI, executes the
ordinary five-pair, exact eight psycho/speed, and B,C,C,B 87ordered/JFR lanes
under bounded process groups, HMAC-seals the complete evidence contract with
the wrapper's private key, and immediately assembles final.json. It never
accepts or re-seals preexisting TAP, timing, command, admission, or JFR output.
USAGE
    exit $status;
}
