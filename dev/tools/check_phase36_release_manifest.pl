#!/usr/bin/env perl

use strict;
use warnings;

use Cwd qw(abs_path getcwd);
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Fcntl qw(:DEFAULT :mode);
use Getopt::Long qw(Configure GetOptionsFromArray);
use IO::Handle;
use IO::Select;
use IO::Uncompress::Unzip qw($UnzipError);
use JSON::PP;
use MIME::Base64 qw(encode_base64);
use POSIX qw(:sys_wait_h setpgid);
use Time::HiRes ();

my $FORK_REF = 'pkg:generic/perlonjava/joni-fork@2.2.7';
my $LEGACY_REF = 'pkg:maven/org.jruby.joni/joni@2.2.7?type=jar';
my $JCODINGS_REF = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';
my $TOOL_DIR = dirname(abs_path(__FILE__))
    or die "Cannot resolve release wrapper directory\n";
my $STREAM_BUFFER = 1024 * 1024;
our $MAX_JSON_BYTES = 64 * 1024 * 1024;
my $MAX_CHILD_OUTPUT = 1024 * 1024;
my $MAX_JAR_ENTRIES = 200_000;
my $MAX_JAR_INVENTORY_BYTES = 64 * 1024 * 1024;
my $MAX_PINNED_FILES = 512;
my $MAX_RETAINED_BYTES = 128 * 1024 * 1024;
our $MAX_SNAPSHOT_FILE_BYTES = 3 * 1024 * 1024 * 1024;
our $MAX_SNAPSHOT_TOTAL_BYTES = 8 * 1024 * 1024 * 1024;
our $FINAL_CHECK_TIMEOUT = 300;
our $FINAL_CHECK_MAX_BYTES = 4 * 1024 * 1024;

our (%PINNED_CHILD_READS, %PINNED_CHILD_OUTPUTS, $PINNED_CHILD_JAR);
our $PROGRAM_OBSERVER;
our $VIRTUAL_OUTPUT_SEQUENCE = 0;

our $EXECUTABLE = !caller;
exit main(@ARGV) if $EXECUTABLE;

sub main {
    my (@arguments) = @_;
    Configure(qw(no_auto_abbrev no_ignore_case no_getopt_compat require_order));
    reject_duplicate_options(\@arguments);
    my ($evidence, $expected_commit, $expected_parent, $output, $help);
    my %authority;
    GetOptionsFromArray(
        \@arguments,
        'evidence=s' => \$evidence,
        'expected-commit=s' => \$expected_commit,
        'expected-parent=s' => \$expected_parent,
        'output=s' => \$output,
        'baseline-source=s' => \$authority{baseline_source},
        'candidate-source=s' => \$authority{candidate_source},
        'perl5-source=s' => \$authority{perl5_source},
        'baseline-jar=s' => \$authority{baseline_jar},
        'candidate-jar=s' => \$authority{candidate_jar},
        'baseline-launcher=s' => \$authority{baseline_launcher},
        'candidate-launcher=s' => \$authority{candidate_launcher},
        'interpreter-launcher=s' => \$authority{interpreter_launcher},
        'java=s' => \$authority{java},
        'perl=s' => \$authority{perl},
        'git=s' => \$authority{git},
        'ps=s' => \$authority{ps},
        'uptime=s' => \$authority{uptime},
        'jfr-tool=s' => \$authority{jfr_tool},
        'jfc=s' => \$authority{jfc},
        'time=s' => \$authority{time_executable},
        'ordered-test-source=s' => \$authority{ordered_test_source},
        'ordered-fixture-manifest=s' => \$authority{ordered_fixture_manifest},
        'dbix-archive=s' => \$authority{dbix_archive},
        'authority-key=s' => \$authority{authority_key},
        'help' => \$help,
    ) or usage(2);
    usage(0) if $help;
    usage(2) if @arguments;
    die "--evidence is required\n" unless defined($evidence) && length($evidence);
    die "--expected-commit must be a full Git SHA\n"
        unless defined($expected_commit)
            && $expected_commit =~ /\A[0-9a-f]{40}\z/;
    die "--expected-parent must be a full Git SHA\n"
        unless defined($expected_parent)
            && $expected_parent =~ /\A[0-9a-f]{40}\z/;
    die "--output is required for an authoritative release\n"
        unless defined($output) && length($output);
    die "Refusing to overwrite output $output\n" if -e $output;
    $authority{expected_commit} = $expected_commit;
    $authority{expected_parent} = $expected_parent;
    my @missing = grep { !defined($authority{$_}) || !length($authority{$_}) }
        qw(baseline_source candidate_source perl5_source baseline_jar
            candidate_jar baseline_launcher candidate_launcher
            interpreter_launcher java perl git ps uptime jfr_tool jfc
            time_executable ordered_test_source ordered_fixture_manifest
            dbix_archive authority_key);
    die "Missing required authority inputs: @missing\n" if @missing;

    my $sealed = seal_evidence($evidence);
    my $evidence_path = $sealed->{original_evidence};
    my $evidence_sha256 = $sealed->{evidence_sha256};
    my $document = decode_json_object($sealed->{evidence_bytes},
        'acceptance evidence', $evidence_path);
    assert_legacy_artifacts_confined($document, $sealed->{original_root});
    my $trusted = prepare_authority_inputs(\%authority);
    pin_validation_inputs($sealed, $trusted);
    my $final = select_final_performance_artifact(
        $document, $sealed, $trusted);
    my $legacy = run_legacy_checker(
        $sealed->{snapshot_evidence}, $expected_commit, $sealed);
    die "Legacy acceptance checker did not produce an authoritative strict report\n"
        unless true_value($legacy->{summary}{authoritative})
            && ($legacy->{check_mode} // '') eq 'strict'
            && ($legacy->{expected_commit} // '') eq $expected_commit;
    die "Legacy acceptance checker did not delegate performance authority\n"
        unless (($legacy->{gates}{performance} // {})
            ->{performance_authority} // '') eq 'final-release-wrapper';

    my $performance = run_final_performance_checker(
        $sealed, $final, $trusted);

    my $strict = verify_strict_notice_artifact(
        $evidence_path, $document, $expected_commit, $sealed);

    final_identity_barrier($sealed, $trusted);

    my $report = {
        schema_version => 1,
        kind => 'phase36-release-manifest',
        authoritative => JSON::PP::true,
        source_commit => $expected_commit,
        evidence_path => $evidence_path,
        evidence_sha256 => $evidence_sha256,
        legacy_checker => {
            check_mode => 'strict',
            authoritative => JSON::PP::true,
            performance_authority => 'final-release-wrapper',
        },
        final_performance => $performance,
        strict_notice_license => $strict,
    };
    my $rendered = JSON::PP->new->utf8->canonical->pretty->encode($report);
    publish_atomic($output, $rendered);
    return 0;
}

sub reject_duplicate_options {
    my ($arguments) = @_;
    my %seen;
    for my $argument (@$arguments) {
        next unless defined($argument) && $argument =~ /\A--([^=]+)(?:=|\z)/;
        die "Duplicate option --$1 is forbidden\n" if $seen{$1}++;
    }
}

sub prepare_authority_inputs {
    my ($option) = @_;
    die "Phase 36 final performance checking is unsupported on Windows by the accepted A231 contract\n"
        if $^O eq 'MSWin32';
    my %trusted = %$option;
    for my $field (qw(baseline_source candidate_source perl5_source)) {
        $trusted{$field} = canonical_authority_directory(
            $trusted{$field}, $field);
    }
    my %executable = map { $_ => 1 }
        qw(java perl git ps uptime baseline_launcher candidate_launcher
            interpreter_launcher jfr_tool time_executable);
    for my $field (qw(baseline_jar candidate_jar baseline_launcher
            candidate_launcher interpreter_launcher java perl git ps uptime
            jfr_tool jfc time_executable ordered_test_source
            ordered_fixture_manifest dbix_archive authority_key)) {
        $trusted{records}{$field} = authority_file_record(
            $trusted{$field}, $field, $executable{$field});
        $trusted{$field} = $trusted{records}{$field}{path};
    }
    die "--perl does not identify the interpreter executing the wrapper\n"
        unless $trusted{records}{perl}{sha256} eq sha256_file_streaming($^X);
    my $state = trusted_source_state(\%trusted);
    die "Candidate source differs from --expected-commit\n"
        unless $state->{candidate_source_commit} eq $trusted{expected_commit};
    die "Candidate actual parent differs from --expected-parent\n"
        unless $state->{candidate_parent_commit} eq $trusted{expected_parent};
    die "Baseline source differs from the candidate exact parent\n"
        unless $state->{baseline_source_commit} eq $trusted{expected_parent};
    $trusted{source_state} = $state;
    return \%trusted;
}

sub canonical_authority_directory {
    my ($path, $label) = @_;
    die "Authority-selected $label must be an absolute canonical directory\n"
        unless defined($path) && !ref($path)
            && File::Spec->file_name_is_absolute($path) && -d $path;
    my @st = Time::HiRes::lstat($path);
    my $canonical = abs_path($path);
    die "Authority-selected $label must not be a symlink and must be canonical\n"
        unless @st && !S_ISLNK($st[2]) && defined($canonical)
            && $canonical eq $path;
    return $canonical;
}

sub authority_file_record {
    my ($path, $label, $executable) = @_;
    die "Authority-selected $label must be an absolute canonical file\n"
        unless defined($path) && !ref($path)
            && File::Spec->file_name_is_absolute($path);
    my @st = Time::HiRes::lstat($path);
    my $canonical = abs_path($path);
    die "Authority-selected $label must be a nonempty regular canonical file\n"
        unless @st && !S_ISLNK($st[2]) && S_ISREG($st[2]) && $st[7] > 0
            && defined($canonical) && $canonical eq $path;
    die "Authority-selected $label is not executable\n"
        if $executable && !-x $path;
    return { path => $path, label => $label, executable => $executable ? 1 : 0,
        sha256 => sha256_file_streaming($path), size => $st[7],
        identity => \@st };
}

sub trusted_source_state {
    my ($trusted) = @_;
    my %state;
    for my $spec ([baseline => 'baseline_source'],
            [candidate => 'candidate_source'], [perl5 => 'perl5_source']) {
        my ($label, $field) = @$spec;
        my $head = trusted_git_output($trusted, $trusted->{$field},
            qw(rev-parse HEAD));
        $head =~ s/\s+\z//;
        die "Authority-selected $label source Git identity is malformed\n"
            unless $head =~ /\A[0-9a-f]{40}\z/;
        my $dirty = trusted_git_output($trusted, $trusted->{$field},
            qw(status --porcelain --untracked-files=all));
        die "Authority-selected $label source checkout is not clean\n"
            if length($dirty);
        $state{"${label}_source_commit"} = $head;
    }
    my $parents = trusted_git_output($trusted, $trusted->{candidate_source},
        qw(rev-list --parents -n 1 HEAD));
    $parents =~ s/\s+\z//;
    my @parent = split / /, $parents;
    die "Authority-selected candidate must have exactly one parent\n"
        unless @parent == 2 && $parent[0] eq $state{candidate_source_commit}
            && $parent[1] =~ /\A[0-9a-f]{40}\z/;
    $state{candidate_parent_commit} = $parent[1];
    $state{perl5_commit} = delete $state{perl5_source_commit};
    return \%state;
}

sub trusted_git_output {
    my ($trusted, $directory, @git_arguments) = @_;
    assert_authority_file_record($trusted->{records}{git});
    my @argv = ($trusted->{git}, '--no-pager', '-c', 'core.fsmonitor=false',
        '-c', 'core.hooksPath=/dev/null', '-C', $directory, @git_arguments);
    my ($wait, $stdout, $stderr) = run_exact_process(\@argv, 30, 4 * 1024 * 1024);
    die "Authority-selected Git command failed or emitted diagnostics\n"
        if $wait != 0 || length($stderr);
    assert_authority_file_record($trusted->{records}{git});
    return $stdout;
}

sub select_final_performance_artifact {
    my ($envelope, $sealed, $trusted) = @_;
    my $gates = require_hash($envelope->{gates}, 'acceptance gates');
    my $gate = require_hash($gates->{performance}, 'performance gate');
    die "Performance gate did not pass\n" unless ($gate->{state} // '') eq 'passed';
    my $details = require_hash($gate->{details}, 'performance gate details');
    my %expected = map { $_ => 1 } qw(final_performance_contract
        final_performance_sha256 performance_authority);
    die "Performance gate is legacy-summary-only or has mixed authority\n"
        unless keys(%$details) == keys(%expected)
            && !grep { !$expected{$_} } keys %$details;
    die "Performance gate has an unsupported final contract\n"
        unless ($details->{final_performance_contract} // '') eq
            'phase36-final-performance/v1';
    die "Performance gate did not delegate authority to the final wrapper\n"
        unless ($details->{performance_authority} // '') eq
            'final-release-wrapper';
    my $artifact = require_hash($gate->{artifact}, 'performance artifact');
    my $sha = require_sha($artifact->{sha256}, 'performance artifact SHA-256');
    die "Performance gate final hash does not bind its artifact descriptor\n"
        unless ($details->{final_performance_sha256} // '') eq $sha;
    my $source = resolve_under_root($artifact->{path}, $sealed->{original_root},
        'final performance artifact');
    my $snapshot = snapshot_path($sealed, $source, 'final performance artifact');
    my $record = record_for_snapshot($sealed, $snapshot);
    die "Final performance artifact hash changed after sealing\n"
        unless $record->{sha256} eq $sha;
    my $bytes = read_record_bounded($record, 16 * 1024 * 1024,
        'final performance artifact');
    retain_record_bytes($sealed, $record, $bytes, 'final performance artifact');
    my $document = decode_json_object($bytes, 'final performance artifact',
        $artifact->{path});
    validate_final_performance_document($document, $trusted, $sealed);
    return { descriptor => $artifact, record => $record, document => $document,
        original_path => $artifact->{path}, bytes => $bytes };
}

sub validate_final_performance_document {
    my ($document, $trusted, $sealed) = @_;
    die "Final performance artifact schema is unsupported\n"
        unless ($document->{schema_version} // '') eq '1'
            && ($document->{kind} // '') eq 'phase36-final-performance';
    my $identity = require_hash($document->{identity},
        'final performance identity');
    my $state = $trusted->{source_state};
    for my $field (qw(baseline_source_commit candidate_source_commit
            candidate_parent_commit perl5_commit)) {
        die "Final performance $field differs from wrapper authority\n"
            unless ($identity->{$field} // '') eq ($state->{$field} // '');
    }
    my %mapping = (
        baseline_jar => 'baseline_jar', candidate_jar => 'candidate_jar',
        baseline_launcher => 'baseline_launcher',
        candidate_launcher => 'candidate_launcher',
        interpreter_launcher => 'interpreter_launcher',
        jdk_executable => 'java', perl_interpreter => 'perl',
        git_executable => 'git', ps_executable => 'ps',
        uptime_executable => 'uptime', jfr_tool => 'jfr_tool', jfc => 'jfc',
        time_executable => 'time_executable',
        ordered_test_source => 'ordered_test_source',
        ordered_fixture_manifest => 'ordered_fixture_manifest',
        dbix_archive => 'dbix_archive');
    for my $identity_field (sort keys %mapping) {
        my $descriptor = require_hash($identity->{$identity_field},
            "final performance identity $identity_field");
        my $selected = $trusted->{records}{$mapping{$identity_field}};
        die "Final performance $identity_field differs from wrapper authority\n"
            unless ($descriptor->{sha256} // '') eq $selected->{sha256}
                && ($descriptor->{size} // '') eq $selected->{size};
    }
    my %pinned_mapping = (
        benchmark => 'performance_benchmark',
        ordinary_performance_producer => 'performance_ordinary_producer',
        performance_evaluator => 'performance_module',
        jfr_metrics_producer => 'performance_helper');
    for my $identity_field (sort keys %pinned_mapping) {
        my $descriptor = require_hash($identity->{$identity_field},
            "final performance identity $identity_field");
        my $pinned = $sealed->{inputs}{$pinned_mapping{$identity_field}};
        die "Final performance $identity_field differs from pinned candidate input\n"
            unless ($descriptor->{sha256} // '') eq $pinned->{sha256}
                && ($descriptor->{size} // '') eq $pinned->{size};
    }
}

sub run_legacy_checker {
    my ($evidence, $expected_commit, $sealed) = @_;
    $sealed //= seal_evidence($evidence);
    pin_validation_inputs($sealed) unless $sealed->{inputs};
    my $checker = $sealed->{inputs}{legacy}{snapshot};
    my $requirements = $sealed->{inputs}{requirements}{snapshot};
    assert_pinned_input($sealed->{inputs}{legacy});
    assert_pinned_input($sealed->{inputs}{requirements});
    my ($status, $text) = run_pinned_perl_program(
        $sealed->{inputs}{legacy}, $sealed,
        '--requirements', $requirements,
        '--evidence', $evidence,
        '--mode', 'strict',
        '--expected-commit', $expected_commit);
    die "Legacy acceptance checker rejected the release manifest\n"
        if $status != 0;
    assert_pinned_input($sealed->{inputs}{legacy});
    assert_pinned_input($sealed->{inputs}{requirements});
    return decode_json_object($text, 'legacy strict acceptance report',
        'pinned legacy checker output');
}

sub run_final_performance_checker {
    my ($sealed, $final, $trusted) = @_;
    my $checker = $sealed->{inputs}{performance_checker};
    assert_pinned_input($checker);
    assert_pinned_input($_) for values %{$sealed->{inputs}};
    assert_authority_file_record($_) for values %{$trusted->{records}};
    my @argv = ($trusted->{perl}, $checker->{snapshot},
        '--requirements', $sealed->{inputs}{requirements}{snapshot},
        '--evidence', $final->{record}{snapshot},
        '--expected-candidate', $trusted->{expected_commit},
        '--mode', 'strict',
        '--java', $trusted->{java}, '--perl', $trusted->{perl},
        '--git', $trusted->{git}, '--ps', $trusted->{ps},
        '--uptime', $trusted->{uptime},
        '--authority-key', $trusted->{authority_key},
        '--baseline-source', $trusted->{baseline_source},
        '--candidate-source', $trusted->{candidate_source},
        '--perl5-source', $trusted->{perl5_source});
    my ($wait, $stdout, $stderr) = run_exact_process(
        \@argv, $FINAL_CHECK_TIMEOUT, $FINAL_CHECK_MAX_BYTES);
    die "Final performance checker emitted unexpected stderr\n" if length($stderr);
    die "Final performance checker was terminated by signal " . ($wait & 127) . "\n"
        if $wait & 127;
    my $status = $wait >> 8;
    die "Final performance checker rejected strict evidence with exit $status\n"
        if $status != 0;
    die "Final performance checker produced no strict report\n" unless length($stdout);
    die "Final performance checker exposed a private snapshot path\n"
        if index($stdout, $sealed->{owner}) >= 0;
    die "Final performance checker exposed the authority secret path\n"
        if index($stdout, $trusted->{authority_key}) >= 0;
    my $report = decode_json_object($stdout,
        'final performance strict report', 'private bounded checker output');
    die "Final performance checker did not produce one authoritative strict pass\n"
        unless ($report->{schema_version} // '') eq '1'
            && ($report->{check_mode} // '') eq 'strict'
            && ($report->{decision} // '') eq 'passed'
            && true_value($report->{authoritative})
            && ref($report->{envelope_issues}) eq 'ARRAY'
            && !@{$report->{envelope_issues}}
            && ref($report->{evaluation}) eq 'HASH'
            && ($report->{evaluation}{decision} // '') eq 'passed'
            && true_value($report->{evaluation}{verified})
            && ref($report->{evaluation}{issues}) eq 'ARRAY'
            && !@{$report->{evaluation}{issues}}
            && ref($report->{evaluation}{review_stops}) eq 'ARRAY'
            && !@{$report->{evaluation}{review_stops}};
    assert_pinned_input($_) for values %{$sealed->{inputs}};
    assert_authority_file_record($_) for values %{$trusted->{records}};
    return {
        contract => 'phase36-final-performance/v1',
        final_artifact_path => $final->{original_path},
        final_artifact_sha256 => $final->{record}{sha256},
        final_artifact_size => $final->{record}{size},
        strict_report => $report,
        strict_report_sha256 => sha256_hex($stdout),
        strict_report_bytes_base64 => encode_base64($stdout, ''),
        strict_report_final_artifact_sha256 => $final->{record}{sha256},
        checker_inputs => {
            map { $_ => {
                sha256 => $sealed->{inputs}{$_}{sha256},
                size => $sealed->{inputs}{$_}{size},
            } } sort grep { /\Aperformance_/ } keys %{$sealed->{inputs}}
        },
        authority => authority_report($trusted),
    };
}

sub authority_report {
    my ($trusted) = @_;
    return {
        source => { %{$trusted->{source_state}} },
        files => {
            map { $_ => {
                path => $trusted->{records}{$_}{path},
                sha256 => $trusted->{records}{$_}{sha256},
                size => $trusted->{records}{$_}{size},
            } } sort grep { $_ ne 'authority_key' } keys %{$trusted->{records}}
        },
    };
}

sub run_exact_process {
    my ($argv, $timeout, $maximum_bytes) = @_;
    die "Exact child argv is missing\n"
        unless ref($argv) eq 'ARRAY' && @$argv && !grep { !defined($_) || ref($_) } @$argv;
    pipe my $stdout_read, my $stdout_write
        or die "Cannot create exact child stdout pipe: $!\n";
    pipe my $stderr_read, my $stderr_write
        or die "Cannot create exact child stderr pipe: $!\n";
    my $pid = fork();
    die "Cannot fork exact child: $!\n" unless defined $pid;
    if ($pid == 0) {
        close $stdout_read;
        close $stderr_read;
        eval { setpgid(0, 0) } unless $^O eq 'MSWin32';
        open STDOUT, '>&', $stdout_write or die "Cannot capture child stdout: $!\n";
        open STDERR, '>&', $stderr_write or die "Cannot capture child stderr: $!\n";
        close $stdout_write;
        close $stderr_write;
        local %ENV = closed_child_environment();
        exec {$argv->[0]} @$argv;
        die "Cannot execute exact child $argv->[0]: $!\n";
    }
    close $stdout_write;
    close $stderr_write;
    eval { setpgid($pid, $pid) } unless $^O eq 'MSWin32';
    my ($stdout_id, $stderr_id) = (fileno($stdout_read), fileno($stderr_read));
    my %stream = ($stdout_id => ['', $stdout_read, 'stdout'],
        $stderr_id => ['', $stderr_read, 'stderr']);
    my $select = IO::Select->new($stdout_read, $stderr_read);
    my $deadline = Time::HiRes::time() + $timeout;
    my ($wait, $reaped, $timed_out) = (0, 0, 0);
    while ($select->count || !$reaped) {
        if (Time::HiRes::time() >= $deadline) {
            $timed_out = 1;
            last;
        }
        for my $fh ($select->can_read(0.05)) {
            my $count = sysread($fh, my $chunk, 64 * 1024);
            die "Cannot read exact child output: $!\n" unless defined $count;
            if (!$count) {
                $select->remove($fh);
                close $fh;
                next;
            }
            $stream{fileno($fh)}[0] .= $chunk;
            if (length($stream{fileno($fh)}[0]) > $maximum_bytes) {
                terminate_exact_child($pid);
                die "Exact child $stream{fileno($fh)}[2] exceeded bounded output\n";
            }
        }
        if (!$reaped) {
            my $result = waitpid($pid, WNOHANG);
            if ($result == $pid) { $wait = $?; $reaped = 1 }
        }
    }
    if ($timed_out) {
        terminate_exact_child($pid);
        die "Exact child timed out after $timeout seconds\n";
    }
    if (!$reaped) { waitpid($pid, 0); $wait = $? }
    for my $fh ($stdout_read, $stderr_read) {
        next unless defined fileno($fh);
        while (1) {
            my $count = sysread($fh, my $chunk, 64 * 1024);
            die "Cannot finish exact child output: $!\n" unless defined $count;
            last unless $count;
            $stream{fileno($fh)}[0] .= $chunk;
            die "Exact child output exceeded bounded output\n"
                if length($stream{fileno($fh)}[0]) > $maximum_bytes;
        }
        close $fh;
    }
    return ($wait, $stream{$stdout_id}[0] // '',
        $stream{$stderr_id}[0] // '');
}

sub terminate_exact_child {
    my ($pid) = @_;
    if ($^O eq 'MSWin32') {
        kill 'KILL', $pid;
    } else {
        kill 'TERM', -$pid;
        my $deadline = Time::HiRes::time() + 1;
        my $reaped = 0;
        while (Time::HiRes::time() < $deadline) {
            if (waitpid($pid, WNOHANG) == $pid) { $reaped = 1; last }
            Time::HiRes::sleep(0.02);
        }
        kill 'KILL', -$pid;
        waitpid($pid, 0) unless $reaped;
        return;
    }
    waitpid($pid, 0);
}

sub closed_child_environment {
    return (PATH => '', LANG => 'C', LC_ALL => 'C', TZ => 'UTC');
}

sub assert_authority_file_record {
    my ($record) = @_;
    my @now = Time::HiRes::lstat($record->{path});
    die "Authority-selected $record->{label} identity changed\n"
        unless @now && !S_ISLNK($now[2]) && S_ISREG($now[2])
            && same_file_identity($record->{identity}, \@now)
            && sha256_file_streaming($record->{path}) eq $record->{sha256}
            && (!$record->{executable} || -x $record->{path});
}

sub final_identity_barrier {
    my ($sealed, $trusted) = @_;
    my %seen;
    for my $group (qw(snapshots private notice_sources inputs)) {
        for my $record (values %{$sealed->{$group} // {}}) {
            next if $seen{$record}++;
            assert_snapshot_record($record, $record->{label} // 'sealed record');
            assert_source_record_unchanged($record)
                if defined($record->{source}) && defined($record->{source_identity});
        }
    }
    assert_authority_file_record($_) for values %{$trusted->{records}};
    my $now = trusted_source_state($trusted);
    die "Authority-selected source state changed before final publication\n"
        unless canonical($now) eq canonical($trusted->{source_state});
}

sub assert_source_record_unchanged {
    my ($record) = @_;
    my @now = Time::HiRes::lstat($record->{source});
    die "Pinned source changed before final publication: $record->{source}\n"
        unless @now && !S_ISLNK($now[2]) && S_ISREG($now[2])
            && same_file_identity($record->{source_identity}, \@now)
            && sha256_file_streaming($record->{source}) eq $record->{sha256};
}

sub all_pinned_records {
    my ($sealed) = @_;
    my %records;
    for my $group (qw(snapshots private notice_sources)) {
        for my $record (values %{$sealed->{$group} // {}}) {
            $records{$record->{snapshot}} = $record;
            $records{$record->{canonical_snapshot}} = $record
                if defined $record->{canonical_snapshot};
            add_platform_path_aliases(\%records, $record->{snapshot}, $record);
            add_platform_path_aliases(\%records, $record->{canonical_snapshot}, $record)
                if defined $record->{canonical_snapshot};
        }
    }
    for my $record (values %{$sealed->{inputs} // {}}) {
        $records{$record->{snapshot}} = $record;
        $records{$record->{canonical_snapshot}} = $record
            if defined $record->{canonical_snapshot};
        add_platform_path_aliases(\%records, $record->{snapshot}, $record);
        add_platform_path_aliases(\%records, $record->{canonical_snapshot}, $record)
            if defined $record->{canonical_snapshot};
    }
    return \%records;
}

sub add_platform_path_aliases {
    my ($map, $path, $record) = @_;
    return unless $^O eq 'darwin' && defined $path;
    if ($path =~ m{\A/private/var/}) {
        (my $alias = $path) =~ s{\A/private}{};
        $map->{$alias} = $record;
    } elsif ($path =~ m{\A/var/}) {
        $map->{"/private$path"} = $record;
    }
}

sub run_pinned_perl_program {
    my ($program, $sealed, @arguments) = @_;
    my $source = read_record_bounded($program, $MAX_JSON_BYTES,
        $program->{label} // 'pinned Perl program');
    assert_pinned_source_policy($source,
        $program->{label} // 'pinned Perl program');
    my $virtual_output = File::Spec->catfile(
        $sealed->{owner}, 'virtual-program-output-' . $$ . '-'
            . ++$VIRTUAL_OUTPUT_SEQUENCE);
    pipe my $stdout_read, my $stdout_write
        or die "Cannot create pinned program output pipe: $!\n";
    pipe my $result_read, my $result_write
        or die "Cannot create pinned program result pipe: $!\n";
    $PROGRAM_OBSERVER->('before-run', $program, $sealed) if $PROGRAM_OBSERVER;
    # Deliberately do not exec a descriptor pathname.  Perl fork (including
    # Win32's pseudo-fork) clones these Perl filehandles and pipes; the child
    # runs the exact bounded source scalar and duplicates pinned handles with
    # Perl's portable <& form.  No descriptor pseudo-path or inheritable-exec
    # flag is needed.
    my $pid = fork();
    die "Cannot fork pinned Perl program: $!\n" unless defined $pid;
    if ($pid == 0) {
        close $stdout_read;
        close $result_read;
        open STDOUT, '>&', $stdout_write
            or die "Cannot capture pinned program stdout: $!\n";
        open STDERR, '>&', $stdout_write
            or die "Cannot capture pinned program stderr: $!\n";
        close $stdout_write;
        %PINNED_CHILD_READS = %{all_pinned_records($sealed)};
        %PINNED_CHILD_OUTPUTS = ($virtual_output => $result_write);
        $PINNED_CHILD_JAR = $sealed->{active_jar_record};
        local @ARGV = (@arguments, '--output', $virtual_output);
        local $0 = $program->{source};
        my $prefix = <<'PINNED_LOADER';
package Phase36PinnedProgram;
BEGIN {
    *CORE::GLOBAL::open = \&main::pinned_child_open;
    *CORE::GLOBAL::sysopen = \&main::pinned_child_sysopen;
    *CORE::GLOBAL::system = \&main::pinned_child_system;
    *CORE::GLOBAL::readpipe = \&main::pinned_child_readpipe;
    *CORE::GLOBAL::exec = \&main::pinned_child_exec;
}
PINNED_LOADER
        my $ok = eval $prefix . $source;
        if (!$ok && $@) {
            print STDERR "Pinned Perl program failed: $@";
            exit 255;
        }
        exit 0;
    }
    close $stdout_write;
    close $result_write;
    my ($stdout_id, $result_id) = (fileno($stdout_read), fileno($result_read));
    my %buffers = ($stdout_id => '', $result_id => '');
    my $select = IO::Select->new($stdout_read, $result_read);
    while (my @ready = $select->can_read) {
        for my $fh (@ready) {
            my $count = sysread($fh, my $chunk, 64 * 1024);
            die "Cannot read pinned program pipe: $!\n" unless defined $count;
            if (!$count) {
                $select->remove($fh);
                close $fh or die "Cannot close pinned program pipe: $!\n";
                next;
            }
            $buffers{fileno($fh)} .= $chunk;
            if (length($buffers{fileno($fh)}) > $MAX_CHILD_OUTPUT) {
                kill 'KILL', $pid;
                waitpid($pid, 0);
                die "Pinned program output exceeded the bounded capture limit\n";
            }
        }
    }
    waitpid($pid, 0);
    my $wait = $?;
    $PROGRAM_OBSERVER->('after-run', $program, $sealed) if $PROGRAM_OBSERVER;
    die "Pinned Perl program was terminated by signal " . ($wait & 127) . "\n"
        if $wait & 127;
    my $status = $wait >> 8;
    my $stdout = $buffers{$stdout_id} // '';
    my $result = $buffers{$result_id} // '';
    return ($status, length($result) ? $result : $stdout, $stdout);
}

sub assert_pinned_source_policy {
    my ($source, $label) = @_;
    # This is a deliberately conservative raw-source policy, not a general
    # untrusted-Perl sandbox.  The release invariant is narrower: the pinned,
    # checked-in checker and verifier may use only the approved ordinary
    # command surface, whose calls are intercepted below.  Explicit CORE
    # qualification (including old, repeated, or mixed package separators)
    # for a command-capable primitive is forbidden before compilation.  It is
    # acceptable for this check to reject the same bytes in comments/strings.
    my $core_separator = qr/(?:(?:::)|')+/;
    die "Pinned Perl source policy rejects explicit CORE-qualified "
        . "command primitive in $label before compilation\n"
        if $source =~ /(?<![A-Za-z0-9_])CORE$core_separator\s*
            (?:system|open|readpipe|exec)\b/x;
    return 1;
}

sub pinned_child_open (*;$@) {
    my @arguments = @_[1 .. $#_];
    my $record = @arguments >= 2 ? $PINNED_CHILD_READS{$arguments[1]} : undef;
    if (@arguments >= 2 && $arguments[0] =~ /\A</ && $record) {
        return CORE::open($_[0], '<', $record->{retained_bytes})
            if $record->{retained_bytes};
        my $source = rewind_record($record, $record->{label} // 'virtual input');
        return CORE::open($_[0], '<&' . fileno($source));
    }
    my $output = @arguments >= 2 ? $PINNED_CHILD_OUTPUTS{$arguments[1]} : undef;
    if (@arguments >= 2 && $arguments[0] =~ /\A>/ && $output) {
        return CORE::open($_[0], '>&' . fileno($output));
    }
    if (@arguments >= 2 && $arguments[0] eq '-|' && $arguments[1] eq 'jar') {
        die "External jar execution is prohibited\n"
            unless @arguments == 4 && $arguments[2] eq 'tf'
                && $PINNED_CHILD_JAR;
        my $listing = join('', map { "$_\n" }
            jar_inventory_record($PINNED_CHILD_JAR));
        return CORE::open($_[0], '<', \$listing);
    }
    die "External command execution by a pinned program is prohibited\n"
        if (@arguments && ($arguments[0] eq '-|' || $arguments[0] eq '|-'))
            || (@arguments == 1
                && $arguments[0] =~ /(?:\A\||\|\z)/);
    return CORE::open($_[0], @arguments);
}

sub pinned_child_sysopen (*$$;$) {
    my (undef, $path, $mode, $permissions) = @_;
    if (my $output = $PINNED_CHILD_OUTPUTS{$path}) {
        return CORE::open($_[0], '>&' . fileno($output));
    }
    return @_ == 4 ? CORE::sysopen($_[0], $path, $mode, $permissions)
        : CORE::sysopen($_[0], $path, $mode);
}

sub pinned_child_system {
    if (@_ && $_[0] eq 'jar') {
        die "External jar execution is prohibited\n"
            unless @_ == 4 && $_[1] eq 'xf' && $PINNED_CHILD_JAR;
        my ($file, $entry) = @_[2, 3];
        my $bytes = jar_entry_bytes_record($PINNED_CHILD_JAR, $entry,
            $MAX_JSON_BYTES);
        my $target = File::Spec->catfile(getcwd(), File::Spec->splitdir($entry));
        make_path(dirname($target));
        CORE::open my $placeholder, '>:raw', $target or return -1;
        print {$placeholder} "pinned\n" or return -1;
        close $placeholder or return -1;
        my $record = scalar_record($target, $bytes);
        $PINNED_CHILD_READS{$target} = $record;
        my $canonical = abs_path($target);
        $PINNED_CHILD_READS{$canonical} = $record if defined $canonical;
        add_platform_path_aliases(\%PINNED_CHILD_READS, $target, $record);
        add_platform_path_aliases(\%PINNED_CHILD_READS, $canonical, $record);
        return 0;
    }
    die "External command execution by a pinned program is prohibited\n";
}

sub pinned_child_readpipe {
    die "External command execution by a pinned program is prohibited\n";
}

sub pinned_child_exec {
    die "External command execution by a pinned program is prohibited\n";
}

sub scalar_record {
    my ($label, $bytes) = @_;
    CORE::open my $fh, '<', \$bytes or die "Cannot open pinned scalar: $!\n";
    binmode $fh, ':raw';
    return { consumer_fh => $fh, size => length($bytes), label => $label,
        retained_bytes => \$bytes };
}

sub verify_strict_notice_artifact {
    my ($evidence_path, $evidence, $expected_commit, $sealed) = @_;
    $sealed //= seal_evidence($evidence_path);
    die "Strict notice verification expected commit is missing or malformed\n"
        unless defined($expected_commit) && !ref($expected_commit)
            && $expected_commit =~ /\A[0-9a-f]{40}\z/;
    die "Acceptance evidence schema_version must be 1\n"
        unless ($evidence->{schema_version} // 0) == 1;
    die "Acceptance evidence mode must be acceptance\n"
        unless ($evidence->{mode} // '') eq 'acceptance';
    my $identity = require_hash($evidence->{identity}, 'acceptance identity');
    die "Acceptance identity source commit differs from expected release commit\n"
        unless ($identity->{source_commit} // '') eq $expected_commit;
    my $gates = require_hash($evidence->{gates}, 'acceptance gates');
    my $gate = require_hash($gates->{'notice-license'}, 'notice-license gate');
    die "Notice-license gate did not pass\n" unless ($gate->{state} // '') eq 'passed';
    my $artifact = require_hash($gate->{artifact}, 'notice-license artifact');
    my $artifact_sha = require_sha($artifact->{sha256}, 'notice-license artifact SHA-256');
    my $root = $sealed->{original_root};
    my $artifact_path = resolve_under_root($artifact->{path}, $root,
        'notice-license artifact');
    my $artifact_snapshot = snapshot_path($sealed, $artifact_path,
        'notice-license artifact');
    my $artifact_record = record_for_snapshot($sealed, $artifact_snapshot);
    die "Notice-license artifact hash mismatch\n"
        unless sha256_record_streaming($artifact_record) eq $artifact_sha;
    my $artifact_bytes = read_record_bounded($artifact_record,
        $MAX_JSON_BYTES, 'strict notice-license artifact');
    retain_record_bytes($sealed, $artifact_record, $artifact_bytes,
        'strict notice-license artifact');
    my $record = decode_json_object($artifact_bytes,
        'strict notice-license artifact', $artifact_snapshot);
    die "Notice-license artifact schema_version must be 1\n"
        unless ($record->{schema_version} // 0) == 1;
    die "Notice-license artifact has the wrong kind\n"
        unless ($record->{kind} // '') eq 'notice-license';
    die "Notice-license artifact is not verified\n"
        unless true_value($record->{verified});
    for my $field (qw(missing_notices changed_notices missing_licenses changed_licenses)) {
        die "Notice-license artifact $field is missing or nonzero\n"
            unless number($record->{$field}) && $record->{$field} == 0;
    }
    die "Notice-license gate details differ from the sealed verifier artifact\n"
        unless canonical($gate->{details}) eq canonical($record);

    my $sealed_jar_sha = require_sha($identity->{jar_sha256},
        'sealed JAR SHA-256');
    my $sealed_sbom_sha = require_sha($identity->{sbom_sha256},
        'sealed SBOM SHA-256');
    die "Notice-license report JAR hash differs from sealed identity\n"
        unless ($record->{jar_sha256} // '') eq $sealed_jar_sha;
    die "Notice-license report SBOM hash differs from sealed identity\n"
        unless ($record->{sbom_sha256} // '') eq $sealed_sbom_sha;
    my $jar = absolute_report_file($record->{jar_path}, 'reported standalone JAR');
    my $sbom_file = absolute_report_file($record->{sbom_path}, 'reported external SBOM');
    my $jar_snapshot = private_snapshot($sealed, $jar, 'standalone.jar',
        'reported standalone JAR');
    my $sbom_snapshot = private_snapshot($sealed, $sbom_file, 'sbom.json',
        'reported external SBOM');
    my $sbom_record = record_for_snapshot($sealed, $sbom_snapshot);
    my $sbom_bytes = read_record_bounded(
        $sbom_record, $MAX_JSON_BYTES, 'external SBOM');
    retain_record_bytes($sealed, $sbom_record, $sbom_bytes, 'external SBOM');
    die "Standalone JAR hash differs from sealed identity\n"
        unless sha256_record_streaming(record_for_snapshot($sealed, $jar_snapshot))
            eq $sealed_jar_sha;
    die "External SBOM hash differs from sealed identity\n"
        unless sha256_record_streaming($sbom_record)
            eq $sealed_sbom_sha;

    assert_report_contract($record);
    $sealed->{active_jar_record} = record_for_snapshot($sealed, $jar_snapshot);
    assert_strict_verifier_replay(
        $record, $artifact_snapshot, $jar_snapshot, $sbom_snapshot,
        $expected_commit, $sealed);
    assert_embedded_sbom($jar_snapshot, $sbom_snapshot, $sealed);

    return {
        verified => JSON::PP::true,
        contract => 'joni-fork-strict-v1',
        artifact_path => $artifact_path,
        artifact_sha256 => $artifact_sha,
        jar_path => $jar,
        jar_sha256 => $sealed_jar_sha,
        sbom_path => $sbom_file,
        sbom_sha256 => $sealed_sbom_sha,
        embedded_sbom_entries => 1,
        embedded_sbom_byte_equal => JSON::PP::true,
    };
}

sub assert_strict_verifier_replay {
    my ($record, $artifact, $jar, $sbom, $expected_commit, $sealed) = @_;
    die "Strict verifier replay expected commit is missing or malformed\n"
        unless defined($expected_commit) && !ref($expected_commit)
            && $expected_commit =~ /\A[0-9a-f]{40}\z/;
    assert_sbom_expected_commit_streaming(
        record_for_snapshot($sealed, $sbom), $expected_commit);
    my $source_root = $record->{source_root};
    die "Notice-license report source_root is not absolute\n"
        unless defined($source_root) && !ref($source_root)
            && File::Spec->file_name_is_absolute($source_root);
    my $reported_source_root = $source_root;
    $source_root = abs_path($source_root)
        or die "Cannot resolve notice-license report source_root\n";
    die "Notice-license report source_root is not canonical; "
        . "Strict notice-license verifier replay rejected the sealed artifacts\n"
        unless $reported_source_root eq $source_root;
    die "Notice-license report source_root is not a directory\n" unless -d $source_root;
    my $source_snapshot = snapshot_notice_sources($sealed, $source_root);
    pin_validation_inputs($sealed) unless $sealed->{inputs};
    my $verifier = $sealed->{inputs}{verifier}{snapshot};
    assert_pinned_input($sealed->{inputs}{verifier});
    my ($status, $text);
    {
        ($status, $text) = run_pinned_perl_program(
            $sealed->{inputs}{verifier}, $sealed, '--strict',
            '--source-root', $source_snapshot, '--jar', $jar, '--sbom', $sbom,
        );
    }
    die "Strict notice-license verifier replay rejected the sealed artifacts:\n$text"
        if $status != 0;
    assert_pinned_input($sealed->{inputs}{verifier});
    my $replay = decode_json_object($text, 'replayed notice-license artifact',
        'pinned strict verifier output');
    my $translated = translated_replay_record(
        $record, $jar, $sbom, $source_root, $source_snapshot);
    die "Sealed notice-license artifact differs from strict verifier replay\n"
        unless canonical($replay) eq canonical($translated);
}

sub translated_replay_record {
    my ($record, $jar, $sbom, $source_root, $source_snapshot) = @_;
    my $copy = JSON::PP->new->decode(canonical($record));
    for my $field ([jar_path => $jar], [sbom_path => $sbom]) {
        my ($name, $snapshot) = @$field;
        $copy->{$name} = abs_path($snapshot)
            or die "Cannot canonicalize derived $name snapshot\n";
    }
    my $canonical_source_snapshot = abs_path($source_snapshot)
        or die "Cannot canonicalize derived notice source snapshot\n";
    $copy->{source_root} = $canonical_source_snapshot;
    my %notice_path = (
        'joni-license' => ['third_party', 'joni', 'LICENSE'],
        'joni-notice' => ['third_party', 'joni', 'PERLONJAVA-NOTICE.md'],
        'jcodings-license' => ['third_party', 'licenses', 'jcodings-LICENSE.txt'],
    );
    die "Notice-license report notices must contain exactly three records\n"
        unless ref($copy->{notices}) eq 'ARRAY' && @{$copy->{notices}} == 3;
    my %seen;
    for my $notice (@{$copy->{notices}}) {
        die "Notice-license report notice is malformed\n"
            unless ref($notice) eq 'HASH' && exists $notice_path{$notice->{id} // ''}
                && !$seen{$notice->{id}}++;
        my $parts = $notice_path{$notice->{id}};
        my $canonical = File::Spec->catfile($source_root, @$parts);
        die "Notice-license report notice path is not the sealed source path\n"
            unless ($notice->{path} // '') eq $canonical;
        $notice->{path} = File::Spec->catfile($canonical_source_snapshot, @$parts);
    }
    return $copy;
}

sub assert_report_contract {
    my ($record) = @_;
    my $components = $record->{components};
    die "Notice-license report components must contain exactly Joni fork and JCodings\n"
        unless ref($components) eq 'ARRAY' && @$components == 2;
    my @expected = (
        ['org.perlonjava.fork', 'joni-fork', '2.2.7', $FORK_REF, 'MIT'],
        ['org.jruby.jcodings', 'jcodings', '1.0.64', $JCODINGS_REF, 'MIT'],
    );
    for my $wanted (@expected) {
        my ($group, $name, $version, $ref, $license) = @$wanted;
        my @found = grep { ref($_) eq 'HASH'
            && ($_->{group} // '') eq $group && ($_->{name} // '') eq $name }
            @$components;
        die "Notice-license report is missing or duplicates $name\n" unless @found == 1;
        my $component = $found[0];
        die "Notice-license report has wrong $name identity\n"
            unless ($component->{version} // '') eq $version
                && ($component->{bom_ref} // '') eq $ref
                && ($component->{purl} // '') eq $ref
                && ($component->{license} // '') eq $license;
    }
    die "Notice-license report contains legacy Maven Joni identity\n"
        if grep { ref($_) eq 'HASH'
            && (($_->{bom_ref} // '') eq $LEGACY_REF
                || ($_->{purl} // '') eq $LEGACY_REF
                || (($_->{group} // '') eq 'org.jruby.joni'
                    && ($_->{name} // '') eq 'joni')) } @$components;
    my $expected_relations = [
        { from => 'perlonjava', to => $FORK_REF },
        { from => $FORK_REF, to => $JCODINGS_REF },
    ];
    die "Notice-license report relationships are not the exact strict fork contract\n"
        unless canonical($record->{relationships}) eq canonical($expected_relations);
}

sub assert_external_sbom {
    my ($sbom, $expected_commit) = @_;
    die "External SBOM is not CycloneDX\n"
        unless ($sbom->{bomFormat} // '') eq 'CycloneDX';
    my $metadata = require_hash($sbom->{metadata}, 'external SBOM metadata');
    my $root = require_hash($metadata->{component}, 'external SBOM root component');
    die "External SBOM root component is not perlonjava\n"
        unless ($root->{'bom-ref'} // '') eq 'perlonjava';
    my $components = $sbom->{components};
    my $dependencies = $sbom->{dependencies};
    die "External SBOM has no components array\n" unless ref($components) eq 'ARRAY';
    die "External SBOM has no dependencies array\n" unless ref($dependencies) eq 'ARRAY';
    assert_unique_component_ids($components);
    die "External SBOM contains legacy Maven Joni identity\n"
        if grep { ref($_) eq 'HASH'
            && (($_->{'bom-ref'} // '') eq $LEGACY_REF
                || ($_->{purl} // '') eq $LEGACY_REF
                || (($_->{group} // '') eq 'org.jruby.joni'
                    && ($_->{name} // '') eq 'joni')) } @$components;
    my $fork = exact_component($components, 'org.perlonjava.fork', 'joni-fork',
        '2.2.7', $FORK_REF, 'MIT');
    exact_component($components, 'org.jruby.jcodings', 'jcodings',
        '1.0.64', $JCODINGS_REF, 'MIT');
    assert_properties($fork, {
        'perlonjava:vendored' => 'true',
        'perlonjava:modified' => 'true',
        'perlonjava:vendored-source-path' => 'third_party/joni',
        'perlonjava:source-commit' => $expected_commit,
        'perlonjava:upstream-maven-coordinate' => 'org.jruby.joni:joni:2.2.7',
        'perlonjava:upstream-tag' => 'joni-2.2.7',
        'perlonjava:upstream-commit' =>
            '57fd57b4f977813a7b4b35e0179943b1f06f51d7',
    });
    assert_relation($dependencies, 'perlonjava', $FORK_REF, 0,
        'PerlOnJava -> Joni fork');
    assert_relation($dependencies, $FORK_REF, $JCODINGS_REF, 1,
        'Joni fork -> JCodings');
}

sub assert_sbom_expected_commit_streaming {
    my ($record, $expected_commit) = @_;
    my $fh = rewind_record($record, 'external SBOM');
    my $stream = { fh => $fh, buffer => '', offset => 0, eof => 0,
        fork_count => 0, commit_count => 0, commit_value => undef };
    json_stream_value($stream, [], 0);
    json_stream_space($stream);
    die "External SBOM contains trailing JSON data\n"
        if defined json_stream_peek($stream);
    die "External SBOM is not CycloneDX\n"
        unless ($stream->{bom_format} // '') eq 'CycloneDX';
    die "External SBOM is missing or duplicates the Joni fork component\n"
        unless $stream->{fork_count} == 1;
    die "External SBOM Joni fork has missing or duplicate perlonjava:source-commit property\n"
        unless $stream->{commit_count} == 1;
    die "External SBOM Joni fork has wrong perlonjava:source-commit property\n"
        unless ($stream->{commit_value} // '') eq $expected_commit;
    die "External SBOM Joni fork -> JCodings relationship is not exact\n"
        unless ($stream->{fork_relation_count} // 0) == 1
            && $stream->{fork_relation_exact};
}

sub json_stream_value {
    my ($stream, $path, $depth) = @_;
    die "External SBOM JSON nesting exceeds bound\n" if $depth > 256;
    json_stream_space($stream);
    my $next = json_stream_peek($stream);
    die "Unexpected end of external SBOM JSON\n" unless defined $next;
    return json_stream_object($stream, $path, $depth + 1) if $next eq '{';
    return json_stream_array($stream, $path, $depth + 1) if $next eq '[';
    return json_stream_string($stream) if $next eq '"';
    my $token = '';
    while (defined($next = json_stream_peek($stream))
            && $next !~ /[\s,\]\}]/) {
        $token .= json_stream_get($stream);
        die "External SBOM scalar token exceeds bound\n" if length($token) > 1024;
    }
    die "Malformed external SBOM scalar\n"
        unless $token =~ /\A(?:null|true|false|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)\z/;
    return $token;
}

sub json_stream_object {
    my ($stream, $path, $depth) = @_;
    json_stream_expect($stream, '{');
    my %selected;
    json_stream_space($stream);
    unless ((json_stream_peek($stream) // '') eq '}') {
        while (1) {
            die "External SBOM object key is not a string\n"
                unless (json_stream_peek($stream) // '') eq '"';
            my $key = json_stream_string($stream);
            json_stream_space($stream);
            json_stream_expect($stream, ':');
            my $value = json_stream_value($stream, [@$path, $key], $depth);
            if (object_is_component($path) && $key eq 'bom-ref' && !ref($value)) {
                $selected{bom_ref} = $value;
            } elsif (!@$path && $key eq 'bomFormat' && !ref($value)) {
                $stream->{bom_format} = $value;
            } elsif (object_is_property($path) && ($key eq 'name' || $key eq 'value')
                    && !ref($value)) {
                $selected{$key} = $value;
            } elsif (object_is_component($path) && $key eq 'properties'
                    && ref($value) eq 'HASH') {
                $selected{properties} = $value;
            } elsif (object_is_dependency($path) && $key eq 'ref' && !ref($value)) {
                $selected{ref} = $value;
            } elsif (object_is_dependency($path) && $key eq 'dependsOn'
                    && ref($value) eq 'HASH') {
                $selected{depends} = $value;
            }
            json_stream_space($stream);
            last if (json_stream_peek($stream) // '') eq '}';
            json_stream_expect($stream, ',');
            json_stream_space($stream);
        }
    }
    json_stream_expect($stream, '}');
    if (object_is_property($path)) {
        return { property_matches => (($selected{name} // '')
                eq 'perlonjava:source-commit') ? 1 : 0,
            property_value => $selected{value} };
    }
    if (object_is_component($path)
            && ($selected{bom_ref} // '') eq $FORK_REF) {
        $stream->{fork_count}++;
        my $properties = $selected{properties} // {};
        $stream->{commit_count} += $properties->{matches} // 0;
        $stream->{commit_value} = $properties->{value}
            if ($properties->{matches} // 0) == 1;
    }
    if (object_is_dependency($path) && ($selected{ref} // '') eq $FORK_REF) {
        $stream->{fork_relation_count}++;
        my $depends = $selected{depends} // {};
        $stream->{fork_relation_exact} = ($depends->{count} // 0) == 1
            && ($depends->{matching} // 0) == 1 && !($depends->{unexpected} // 0);
    }
    return {};
}

sub json_stream_array {
    my ($stream, $path, $depth) = @_;
    json_stream_expect($stream, '[');
    my ($index, $matches, $value, $matching, $unexpected) = (0, 0, undef, 0, 0);
    json_stream_space($stream);
    unless ((json_stream_peek($stream) // '') eq ']') {
        while (1) {
            my $item = json_stream_value($stream, [@$path, $index++], $depth);
            if (array_is_properties($path) && ref($item) eq 'HASH'
                    && $item->{property_matches}) {
                $matches++;
                $value = $item->{property_value};
            } elsif (array_is_depends_on($path)) {
                $matching++ if !ref($item) && $item eq $JCODINGS_REF;
                $unexpected++ if ref($item) || $item ne $JCODINGS_REF;
            }
            json_stream_space($stream);
            last if (json_stream_peek($stream) // '') eq ']';
            json_stream_expect($stream, ',');
            json_stream_space($stream);
        }
    }
    json_stream_expect($stream, ']');
    return { matches => $matches, value => $value } if array_is_properties($path);
    return { count => $index, matching => $matching, unexpected => $unexpected }
        if array_is_depends_on($path);
    return [];
}

sub object_is_component {
    my ($path) = @_;
    return @$path == 2 && $path->[0] eq 'components' && $path->[1] =~ /\A\d+\z/;
}

sub object_is_property {
    my ($path) = @_;
    return @$path == 4 && $path->[0] eq 'components'
        && $path->[1] =~ /\A\d+\z/ && $path->[2] eq 'properties'
        && $path->[3] =~ /\A\d+\z/;
}

sub object_is_dependency {
    my ($path) = @_;
    return @$path == 2 && $path->[0] eq 'dependencies' && $path->[1] =~ /\A\d+\z/;
}

sub array_is_properties {
    my ($path) = @_;
    return @$path == 3 && $path->[0] eq 'components'
        && $path->[1] =~ /\A\d+\z/ && $path->[2] eq 'properties';
}

sub array_is_depends_on {
    my ($path) = @_;
    return @$path == 3 && $path->[0] eq 'dependencies'
        && $path->[1] =~ /\A\d+\z/ && $path->[2] eq 'dependsOn';
}

sub json_stream_space {
    my ($stream) = @_;
    json_stream_get($stream) while defined(json_stream_peek($stream))
        && json_stream_peek($stream) =~ /\s/;
}

sub json_stream_string {
    my ($stream) = @_;
    my $raw = json_stream_get($stream);
    die "External SBOM string did not start with a quote\n" unless $raw eq '"';
    my $escaped = 0;
    while (1) {
        my $char = json_stream_get($stream);
        die "Unterminated external SBOM string\n" unless defined $char;
        $raw .= $char;
        die "External SBOM string exceeds bound\n" if length($raw) > 1024 * 1024;
        if (!$escaped && $char eq '"') { last }
        if (!$escaped && $char eq '\\') { $escaped = 1 }
        else { $escaped = 0 }
    }
    my $decoded = eval { JSON::PP->new->utf8->decode($raw) };
    die "Malformed external SBOM JSON string\n" if $@ || ref($decoded);
    return $decoded;
}

sub json_stream_expect {
    my ($stream, $wanted) = @_;
    my $found = json_stream_get($stream);
    die "Malformed external SBOM JSON: expected $wanted\n"
        unless defined($found) && $found eq $wanted;
}

sub json_stream_peek {
    my ($stream) = @_;
    json_stream_fill($stream) unless $stream->{offset} < length($stream->{buffer});
    return undef if $stream->{offset} >= length($stream->{buffer});
    return substr($stream->{buffer}, $stream->{offset}, 1);
}

sub json_stream_get {
    my ($stream) = @_;
    my $char = json_stream_peek($stream);
    $stream->{offset}++ if defined $char;
    return $char;
}

sub json_stream_fill {
    my ($stream) = @_;
    return if $stream->{eof};
    my $count = sysread($stream->{fh}, my $chunk, 64 * 1024);
    die "Cannot stream external SBOM: $!\n" unless defined $count;
    if (!$count) {
        $stream->{eof} = 1;
        $stream->{buffer} = '';
        $stream->{offset} = 0;
        return;
    }
    $stream->{buffer} = $chunk;
    $stream->{offset} = 0;
}

sub assert_embedded_sbom {
    my ($jar, $external_file, $sealed) = @_;
    my $entry = 'META-INF/sbom/sbom.json';
    my $jar_record = record_for_snapshot($sealed, $jar);
    my %entries;
    $entries{$_}++ for jar_inventory_record($jar_record);
    die "Standalone JAR must contain exactly one $entry\n"
        unless ($entries{$entry} // 0) == 1;
    my $embedded = jar_entry_bytes_record($jar_record, $entry, $MAX_JSON_BYTES);
    my $external = record_for_snapshot($sealed, $external_file);
    die "Standalone JAR embedded SBOM bytes differ from external SBOM\n"
        unless $embedded eq read_record_bounded(
            $external, $MAX_JSON_BYTES, 'external SBOM');
}

sub exact_component {
    my ($components, $group, $name, $version, $ref, $license) = @_;
    my @found = grep { ref($_) eq 'HASH'
        && ($_->{group} // '') eq $group && ($_->{name} // '') eq $name }
        @$components;
    die "External SBOM is missing or duplicates $name\n" unless @found == 1;
    my $component = $found[0];
    die "External SBOM has wrong $name identity\n"
        unless ($component->{version} // '') eq $version
            && ($component->{'bom-ref'} // '') eq $ref
            && ($component->{purl} // '') eq $ref;
    my @licenses = map { ref($_) eq 'HASH' && ref($_->{license}) eq 'HASH'
        ? ($_->{license}{id} // '') : () } @{$component->{licenses} // []};
    die "External SBOM has wrong $name license\n"
        unless @licenses == 1 && $licenses[0] eq $license;
    return $component;
}

sub assert_unique_component_ids {
    my ($components) = @_;
    my (%refs, %purls);
    for my $component (@$components) {
        die "External SBOM component is not an object\n"
            unless ref($component) eq 'HASH';
        for my $field (['bom-ref', \%refs], ['purl', \%purls]) {
            my ($name, $seen) = @$field;
            next unless defined($component->{$name}) && length($component->{$name});
            die "External SBOM has duplicate $name $component->{$name}\n"
                if $seen->{$component->{$name}}++;
        }
    }
}

sub assert_properties {
    my ($component, $required) = @_;
    my %found;
    my $properties = $component->{properties};
    die "External SBOM Joni fork has no provenance properties\n"
        unless ref($properties) eq 'ARRAY';
    for my $property (@$properties) {
        next unless ref($property) eq 'HASH';
        my $name = $property->{name} // '';
        push @{$found{$name}}, $property->{value} // '' if exists $required->{$name};
    }
    for my $name (sort keys %$required) {
        my $values = $found{$name} // [];
        die "External SBOM Joni fork has missing or duplicate $name property\n"
            unless @$values == 1;
        my $expected = $required->{$name};
        my $matches = ref($expected) eq 'Regexp'
            ? $values->[0] =~ $expected : $values->[0] eq $expected;
        die "External SBOM Joni fork has wrong $name property\n" unless $matches;
    }
}

sub assert_relation {
    my ($dependencies, $from, $to, $exact, $label) = @_;
    my @relations = grep { ref($_) eq 'HASH' && ($_->{ref} // '') eq $from }
        @$dependencies;
    die "External SBOM is missing or duplicates $label relation\n"
        unless @relations == 1;
    my $edges = $relations[0]{dependsOn};
    die "External SBOM $label relation is malformed\n" unless ref($edges) eq 'ARRAY';
    my @matching = grep { defined($_) && !ref($_) && $_ eq $to } @$edges;
    die "External SBOM is missing or duplicates $label edge\n" unless @matching == 1;
    die "External SBOM $label relationship is not exact\n"
        if $exact && (@$edges != 1 || $edges->[0] ne $to);
}

sub walk_jar_record {
    my ($record, $wanted, $limit) = @_;
    my $fh = rewind_record($record, 'standalone JAR');
    # Read ZIP members directly from the pinned archive descriptor.  This
    # replaces all pathname-based execution of the external jar program.
    my $zip = IO::Uncompress::Unzip->new($fh, MultiStream => 0,
        Transparent => 0)
        or die "Cannot read pinned standalone JAR: $UnzipError\n";
    my (@names, $found, $inventory_bytes);
    while (1) {
        my $header = $zip->getHeaderInfo;
        my $name = $header->{Name};
        die "JAR entry name is missing or exceeds bound\n"
            unless defined($name) && length($name) <= 8192;
        $inventory_bytes += length($name) + 1;
        die "JAR entry inventory bytes exceed bound\n"
            if $inventory_bytes > $MAX_JAR_INVENTORY_BYTES;
        die "JAR entry inventory exceeds bound\n"
            if push(@names, $name) > $MAX_JAR_ENTRIES;
        my $bytes = '';
        while (1) {
            my $count = $zip->read(my $chunk, 64 * 1024);
            die "Cannot stream pinned JAR entry $name: $UnzipError\n"
                unless defined $count;
            last unless $count;
            if (defined($wanted) && $name eq $wanted) {
                $bytes .= $chunk;
                die "Pinned JAR entry $name exceeds bounded limit\n"
                    if length($bytes) > $limit;
            }
        }
        $found = $bytes if defined($wanted) && $name eq $wanted;
        last unless $zip->nextStream;
    }
    return (\@names, $found);
}

sub jar_inventory_record {
    my ($record) = @_;
    my ($names) = walk_jar_record($record, undef, 0);
    return @$names;
}

sub jar_entry_bytes_record {
    my ($record, $entry, $limit) = @_;
    my ($names, $bytes) = walk_jar_record($record, $entry, $limit);
    my $count = grep { $_ eq $entry } @$names;
    die "Standalone JAR must contain exactly one $entry\n" unless $count == 1;
    return $bytes;
}

sub seal_evidence {
    my ($path) = @_;
    my $original = absolute_regular_path($path, 'acceptance evidence');
    my @evidence_metadata = Time::HiRes::lstat($original);
    die "Cannot inspect acceptance evidence $original: $!\n"
        unless @evidence_metadata;
    die "Acceptance evidence JSON exceeds bounded metadata limit\n"
        if $evidence_metadata[7] > $MAX_JSON_BYTES;
    my $root = abs_path(dirname($original))
        or die "Cannot resolve sealed evidence root\n";
    my $directory = tempdir(CLEANUP => 1);
    my $snapshot_root = File::Spec->catdir($directory, 'evidence');
    make_path($snapshot_root);
    my $relative = File::Spec->abs2rel($original, $root);
    my $snapshot_evidence = File::Spec->catfile(
        $snapshot_root, File::Spec->splitdir($relative));
    my $sealed = {
        owner => $directory,
        original_evidence => $original,
        original_root => $root,
        snapshot_root => $snapshot_root,
        snapshots => {}, private => {}, copied_bytes => 0, copied_files => 0,
        retained_bytes => 0,
    };
    my $evidence_copy = snapshot_file($sealed,
        $original, $snapshot_evidence, 'acceptance evidence');
    my $bytes = read_record_bounded(
        $evidence_copy, $MAX_JSON_BYTES, 'acceptance evidence');
    my $document = decode_json_object($bytes, 'acceptance evidence', $original);
    $sealed->{snapshot_evidence} = $snapshot_evidence;
    $sealed->{evidence_sha256} = $evidence_copy->{sha256};
    $sealed->{evidence_bytes} = $bytes;
    $sealed->{snapshots}{$original} = $evidence_copy;
    retain_record_bytes($sealed, $evidence_copy, $bytes, 'acceptance evidence');
    my @queue;
    my $gates = ref($document->{gates}) eq 'HASH' ? $document->{gates} : {};
    for my $gate_id (sort keys %$gates) {
        my $gate = require_hash($gates->{$gate_id}, "$gate_id gate");
        enqueue_descriptor(\@queue, $gate->{artifact}, $root, $root,
            "$gate_id gate artifact", 1);
        discover_descriptors($gate->{details}, $root, $root,
            "$gate_id gate details", \@queue);
    }
    my %processed;
    while (my $item = shift @queue) {
        my $source = descriptor_source($item->{descriptor}{path},
            $item->{base}, $root, $item->{label});
        my $expected = $item->{descriptor}{sha256};
        if (my $prior = $processed{$source}) {
            die "$item->{label} has conflicting SHA-256 descriptors\n"
                unless $prior eq $expected;
            next;
        }
        $processed{$source} = $expected;
        my $rel = File::Spec->abs2rel($source, $root);
        my $target = File::Spec->catfile(
            $snapshot_root, File::Spec->splitdir($rel));
        die "Descriptor count exceeds pinned input bound\n"
            if $sealed->{copied_files} >= $MAX_PINNED_FILES;
        my $copy = snapshot_file($sealed,
            $source, $target, $item->{label}, $expected, $root);
        $sealed->{snapshots}{$source} = $copy;
        if ($source =~ /\.json\z/i) {
            die "$item->{label} JSON exceeds bounded descriptor limit\n"
                if $copy->{size} > $MAX_JSON_BYTES;
            my $nested_bytes = read_record_bounded(
                $copy, $MAX_JSON_BYTES, $item->{label});
            retain_record_bytes($sealed, $copy, $nested_bytes, $item->{label});
            my $nested = decode_json_value(
                $nested_bytes, $item->{label}, $source);
            discover_descriptors($nested, dirname($source), $root,
                "$item->{label} document", \@queue);
        }
    }
    return $sealed;
}

sub assert_legacy_artifacts_confined {
    my ($document, $root) = @_;
    my $gates = $document->{gates};
    return unless ref($gates) eq 'HASH';
    for my $gate_id (sort keys %$gates) {
        my $gate = $gates->{$gate_id};
        next unless ref($gate) eq 'HASH';
        assert_artifact_descriptor($gate->{artifact}, $root,
            "$gate_id gate artifact");
        walk_artifact_descriptors($gate->{details}, $root,
            "$gate_id gate details");
    }
}

sub walk_artifact_descriptors {
    my ($value, $root, $label) = @_;
    my @found;
    discover_descriptors($value, $root, $root, $label, \@found);
}

sub assert_artifact_descriptor {
    my ($descriptor, $root, $label) = @_;
    my @queue;
    enqueue_descriptor(\@queue, $descriptor, $root, $root, $label, 1);
    descriptor_source($descriptor->{path}, $root, $root, $label);
}

sub descriptor_shape {
    my ($value) = @_;
    return descriptor_candidate($value)
        && defined($value->{path}) && !ref($value->{path}) && length($value->{path})
        && !unsafe_relative($value->{path})
        && defined($value->{sha256}) && !ref($value->{sha256})
        && $value->{sha256} =~ /\A[0-9a-f]{64}\z/;
}

sub descriptor_candidate {
    my ($value) = @_;
    return 0 unless ref($value) eq 'HASH'
        && exists($value->{path}) && exists($value->{sha256})
        && defined($value->{path}) && !ref($value->{path})
        && defined($value->{sha256}) && !ref($value->{sha256})
        && $value->{sha256} =~ /\A[0-9a-f]{64}\z/;
    my %permitted = map { $_ => 1 } qw(path sha256 kind size bytes media_type);
    return !(grep { !$permitted{$_} } keys %$value);
}

sub enqueue_descriptor {
    my ($queue, $descriptor, $base, $root, $label, $required) = @_;
    if (!descriptor_shape($descriptor)) {
        if ($required && descriptor_candidate($descriptor)
                && defined($descriptor->{path}) && !ref($descriptor->{path})
                && unsafe_relative($descriptor->{path})) {
            die "$label is outside the sealed evidence root\n";
        }
        die "$label descriptor is missing or malformed\n" if $required;
        return;
    }
    descriptor_source($descriptor->{path}, $base, $root, $label);
    push @$queue, { descriptor => $descriptor, base => $base, label => $label };
}

sub discover_descriptors {
    my ($value, $base, $root, $label, $queue) = @_;
    return unless ref($value);
    if (descriptor_shape($value)) {
        enqueue_descriptor($queue, $value, $base, $root, $label, 0);
        return;
    }
    if (descriptor_candidate($value)) {
        enqueue_descriptor($queue, $value, $base, $root, $label, 1);
        return;
    }
    if (ref($value) eq 'HASH') {
        discover_descriptors($value->{$_}, $base, $root, "$label $_", $queue)
            for sort keys %$value;
    } elsif (ref($value) eq 'ARRAY') {
        for my $index (0 .. $#$value) {
            discover_descriptors($value->[$index], $base, $root,
                "$label item $index", $queue);
        }
    }
}

sub descriptor_source {
    my ($path, $base, $root, $label) = @_;
    die "$label path is outside the sealed evidence root\n"
        if unsafe_relative($path);
    my $candidate = File::Spec->canonpath(
        File::Spec->catfile($base, File::Spec->splitdir($path)));
    my $relative = File::Spec->abs2rel($candidate, $root);
    die "$label is outside the sealed evidence root\n" if unsafe_relative($relative);
    my $cursor = $root;
    my @parts = File::Spec->splitdir($relative);
    for my $index (0 .. $#parts) {
        $cursor = File::Spec->catfile($cursor, $parts[$index]);
        my @st = Time::HiRes::lstat($cursor);
        die "Cannot inspect $label $cursor: $!\n" unless @st;
        die "$label must not be a symlink or traverse one: $cursor\n"
            if S_ISLNK($st[2]);
        die "$label has a non-directory path component: $cursor\n"
            if $index < $#parts && !S_ISDIR($st[2]);
    }
    return $candidate;
}

sub unsafe_relative {
    my ($path) = @_;
    return 1 if !defined($path) || ref($path) || File::Spec->file_name_is_absolute($path);
    return scalar grep { $_ eq File::Spec->updir || $_ eq '' }
        File::Spec->splitdir($path);
}

sub snapshot_path {
    my ($sealed, $original, $label) = @_;
    my $record = $sealed->{snapshots}{$original}
        or die "$label was not included in the descriptor-driven snapshot\n";
    assert_snapshot_record($record, "$label snapshot");
    return $record->{snapshot};
}

sub private_snapshot {
    my ($sealed, $original, $name, $label) = @_;
    my $resolved = absolute_regular_path($original, $label);
    if (path_under_root($resolved, $sealed->{original_root})
            && $sealed->{snapshots}{$resolved}) {
        return snapshot_path($sealed, $resolved, $label);
    }
    if (my $record = $sealed->{private}{$resolved}) {
        assert_snapshot_record($record, $label);
        return $record->{snapshot};
    }
    my $directory = File::Spec->catdir($sealed->{owner}, 'private');
    make_path($directory);
    my $target = File::Spec->catfile($directory,
        scalar(keys %{$sealed->{private}}) . "-$name");
    die "Private validation input count exceeds bound\n"
        if $sealed->{copied_files} >= $MAX_PINNED_FILES;
    my $record = snapshot_file($sealed, $resolved, $target, $label);
    $sealed->{private}{$resolved} = $record;
    return $record->{snapshot};
}

sub snapshot_notice_sources {
    my ($sealed, $source_root) = @_;
    my $target_root = File::Spec->catdir($sealed->{owner}, 'notice-source');
    my @relative = (
        ['third_party', 'joni', 'LICENSE'],
        ['third_party', 'joni', 'PERLONJAVA-NOTICE.md'],
        ['third_party', 'licenses', 'jcodings-LICENSE.txt'],
    );
    for my $parts (@relative) {
        my $source = File::Spec->catfile($source_root, @$parts);
        my $target = File::Spec->catfile($target_root, @$parts);
        die "Notice source input count exceeds bound\n"
            if $sealed->{copied_files} >= $MAX_PINNED_FILES;
        my $record = eval { snapshot_file($sealed,
            $source, $target, 'notice-license source', undef, $source_root) };
        die "Strict notice-license verifier replay rejected the sealed artifacts:\n$@" if $@;
        my $bytes = read_record_bounded(
            $record, $MAX_JSON_BYTES, 'notice-license source');
        retain_record_bytes($sealed, $record, $bytes, 'notice-license source');
        $sealed->{notice_sources}{$target} = $record;
    }
    return $target_root;
}

sub record_for_snapshot {
    my ($sealed, $path) = @_;
    for my $group (qw(snapshots private notice_sources inputs)) {
        for my $record (values %{$sealed->{$group} // {}}) {
            return $record if $record->{snapshot} eq $path;
        }
    }
    die "No pinned descriptor owns snapshot $path\n";
}

sub path_under_root {
    my ($path, $root) = @_;
    my $relative = File::Spec->abs2rel($path, $root);
    return !unsafe_relative($relative);
}

sub absolute_regular_path {
    my ($path, $label) = @_;
    die "$label path is missing\n" unless defined($path) && !ref($path) && length($path);
    my @metadata = Time::HiRes::lstat($path);
    die "Cannot inspect $label $path: $!\n" unless @metadata;
    die "$label must not be a symlink: $path\n" if S_ISLNK($metadata[2]);
    die "$label is not a regular nonempty file: $path\n"
        unless S_ISREG($metadata[2]) && $metadata[7] > 0;
    my $resolved = abs_path($path) or die "Cannot resolve $label $path\n";
    return $resolved;
}

sub snapshot_file {
    my ($sealed, $path, $target, $label, $expected_sha, $root) = @_;
    my @source = Time::HiRes::lstat($path);
    die "Cannot inspect $label $path: $!\n" unless @source;
    die "$label must not be a symlink: $path\n" if S_ISLNK($source[2]);
    die "$label is not a regular file: $path\n" unless S_ISREG($source[2]);
    my $size = $source[7];
    die "$label exceeds per-file snapshot byte bound\n"
        if $size > $MAX_SNAPSHOT_FILE_BYTES;
    die "$label exceeds aggregate snapshot byte bound\n"
        if $sealed->{copied_bytes} > $MAX_SNAPSHOT_TOTAL_BYTES - $size;
    $sealed->{copied_bytes} += $size;
    $sealed->{copied_files}++;
    my $record = eval { stream_snapshot_file(
        $path, $target, $label, $expected_sha, $root, $size) };
    my $error = $@;
    if (!$record) {
        $sealed->{copied_bytes} -= $size;
        $sealed->{copied_files}--;
        if (-e $target || -l $target) {
            my $removed = unlink $target;
            unless ($removed || (!-e $target && !-l $target)) {
                my $failure = $error || "Cannot snapshot $label\n";
                die $failure . "Cannot remove failed snapshot $target: $!\n";
            }
        }
        die $error || "Cannot snapshot $label\n";
    }
    return $record;
}

sub stream_snapshot_file {
    my ($path, $target, $label, $expected_sha, $root, $reserved_size) = @_;
    my @before = Time::HiRes::lstat($path);
    die "Cannot inspect $label $path: $!\n" unless @before;
    die "$label must not be a symlink: $path\n" if S_ISLNK($before[2]);
    die "$label is not a regular file: $path\n" unless S_ISREG($before[2]);
    my $flags = O_RDONLY;
    $flags |= Fcntl::O_NOFOLLOW() if defined &Fcntl::O_NOFOLLOW;
    sysopen my $fh, $path, $flags or die "Cannot pin $label $path: $!\n";
    binmode $fh, ':raw' or die "Cannot set raw mode for $label $path: $!\n";
    my @opened = Time::HiRes::stat($fh);
    die "Cannot stat pinned $label $path: $!\n" unless @opened;
    die "$label identity changed before it was pinned: $path\n"
        unless same_file_identity(\@before, \@opened);
    pin_observer('opened', $path, $label);
    if (defined $root) {
        my $resolved = abs_path($path)
            or die "Cannot resolve pinned $label $path\n";
        die "$label resolved outside the sealed evidence root: $path\n"
            unless path_under_root($resolved, $root);
    }
    make_path(dirname($target));
    sysopen my $out, $target, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot create private snapshot $target: $!\n";
    binmode $out, ':raw' or die "Cannot set raw snapshot mode $target: $!\n";
    my $digest = Digest::SHA->new(256);
    my $total = 0;
    my $ok = eval {
        while (1) {
            my $count = sysread($fh, my $chunk, $STREAM_BUFFER);
            die "Cannot read pinned $label $path: $!\n" unless defined $count;
            last unless $count;
            $digest->add($chunk);
            $total += $count;
            die "$label grew beyond its reserved snapshot byte bound\n"
                if defined($reserved_size) && $total > $reserved_size;
            my $offset = 0;
            while ($offset < $count) {
                my $written = syswrite($out, $chunk, $count - $offset, $offset);
                die "Cannot write private snapshot $target: $!\n"
                    unless defined($written) && $written > 0;
                $offset += $written;
            }
        }
        checked_flush($out, "private snapshot $target");
        checked_close($out, "private snapshot $target");
        undef $out;
        1;
    };
    my $copy_error = $@;
    if (!$ok) {
        CORE::close($out) if defined $out;
        unlink $target;
        die $copy_error;
    }
    my @after = Time::HiRes::stat($fh);
    die "Cannot restat pinned $label $path: $!\n" unless @after;
    pin_observer('before-path-recheck', $path, $label);
    my @final = Time::HiRes::lstat($path);
    die "$label disappeared while it was pinned: $path\n" unless @final;
    my $sha = $digest->hexdigest;
    die "$label changed while it was pinned: $path\n"
        unless same_file_identity(\@opened, \@after)
            && same_file_identity(\@opened, \@final)
            && $total == $opened[7];
    close $fh or die "Cannot close pinned $label $path: $!\n";
    if (defined $expected_sha && $sha ne $expected_sha) {
        unlink $target;
        die "$label hash mismatch\n";
    }
    chmod 0400, $target or die "Cannot make snapshot immutable $target: $!\n";
    my @snapshot_identity = Time::HiRes::lstat($target);
    sysopen my $consumer, $target, O_RDONLY
        or die "Cannot open pinned snapshot descriptor $target: $!\n";
    binmode $consumer, ':raw'
        or die "Cannot set pinned snapshot descriptor raw mode $target: $!\n";
    my @consumer_identity = Time::HiRes::stat($consumer);
    die "Pinned snapshot descriptor identity differs from created snapshot\n"
        unless same_file_identity(\@snapshot_identity, \@consumer_identity);
    return { source => $path, snapshot => $target, sha256 => $sha,
        size => $total, identity => \@snapshot_identity,
        source_identity => \@final, consumer_fh => $consumer,
        canonical_snapshot => abs_path($target) };
}

our $PIN_OBSERVER;
sub pin_observer {
    $PIN_OBSERVER->(@_) if $PIN_OBSERVER;
    return;
}

sub same_file_identity {
    my ($left, $right) = @_;
    for my $index (0, 1, 2, 7, 9, 10) {
        return 0 unless $left->[$index] == $right->[$index];
    }
    return 1;
}

sub assert_snapshot_record {
    my ($record, $label) = @_;
    my @now = Time::HiRes::lstat($record->{snapshot});
    die "$label identity changed\n"
        unless @now && !S_ISLNK($now[2]) && S_ISREG($now[2])
            && same_file_identity($record->{identity}, \@now)
            && sha256_record_streaming($record) eq $record->{sha256};
}

sub resolve_under_root {
    my ($path, $root, $label) = @_;
    die "$label path is missing\n" unless defined($path) && !ref($path) && length($path);
    die "$label is outside the sealed evidence root\n"
        if File::Spec->file_name_is_absolute($path) || unsafe_relative($path);
    my $candidate = File::Spec->catfile($root, File::Spec->splitdir($path));
    my $resolved = absolute_regular_path($candidate, $label);
    my $relative = File::Spec->abs2rel($resolved, $root);
    die "$label is outside the sealed evidence root\n"
        if File::Spec->file_name_is_absolute($relative)
            || $relative eq File::Spec->updir
            || $relative =~ /\A\.\.(?:[\\\/]|\x00)/;
    return $resolved;
}

sub absolute_report_file {
    my ($path, $label) = @_;
    die "$label path is not absolute\n"
        unless defined($path) && !ref($path) && File::Spec->file_name_is_absolute($path);
    my $resolved = absolute_regular_path($path, $label);
    die "$label path is not canonical\n" unless $path eq $resolved;
    return $resolved;
}

sub pin_validation_inputs {
    my ($sealed, $trusted) = @_;
    return $sealed->{inputs} if $sealed->{inputs};
    my $root = File::Spec->catdir($sealed->{owner}, 'pinned-inputs');
    my @inputs = (
        [legacy => File::Spec->catfile($TOOL_DIR,
            'check_phase36_acceptance_manifest.pl'), 'legacy acceptance checker',
            File::Spec->catfile($root, 'check_phase36_acceptance_manifest.pl'),
            0500, 'dev/tools/check_phase36_acceptance_manifest.pl'],
        [requirements => File::Spec->catfile($TOOL_DIR,
            'phase36_acceptance_requirements.json'), 'acceptance requirements',
            File::Spec->catfile($root, 'phase36_acceptance_requirements.json'),
            0400, 'dev/tools/phase36_acceptance_requirements.json'],
        [verifier => File::Spec->catfile($TOOL_DIR,
            'verify_phase36_notice_license.pl'), 'strict notice-license verifier',
            File::Spec->catfile($root, 'verify_phase36_notice_license.pl'),
            0500, 'dev/tools/verify_phase36_notice_license.pl'],
        [performance_checker => File::Spec->catfile($TOOL_DIR,
            'check_phase36_final_performance.pl'), 'final performance checker',
            File::Spec->catfile($root, 'check_phase36_final_performance.pl'),
            0500, 'dev/tools/check_phase36_final_performance.pl'],
        [performance_module => File::Spec->catfile($TOOL_DIR, 'lib',
            'PerlOnJava', 'Phase36PerformanceEvidence.pm'),
            'final performance support module', File::Spec->catfile($root,
                'lib', 'PerlOnJava', 'Phase36PerformanceEvidence.pm'),
            0400, 'dev/tools/lib/PerlOnJava/Phase36PerformanceEvidence.pm'],
        [performance_helper => File::Spec->catfile($TOOL_DIR,
            'Phase36JfrMetrics.java'), 'final performance JFR helper',
            File::Spec->catfile($root, 'Phase36JfrMetrics.java'),
            0400, 'dev/tools/Phase36JfrMetrics.java'],
        [performance_orchestrator => File::Spec->catfile($TOOL_DIR,
            'run_phase36_final_performance.pl'), 'final performance producer',
            File::Spec->catfile($root, 'run_phase36_final_performance.pl'),
            0500, 'dev/tools/run_phase36_final_performance.pl'],
        [performance_ordinary_producer => File::Spec->catfile($TOOL_DIR,
            'run_phase36_regex_performance.pl'), 'ordinary performance producer',
            File::Spec->catfile($root, 'run_phase36_regex_performance.pl'),
            0500, 'dev/tools/run_phase36_regex_performance.pl'],
        [performance_benchmark => File::Spec->catfile($TOOL_DIR,
            'phase36_regex_benchmark.pl'), 'ordinary performance benchmark',
            File::Spec->catfile($root, 'phase36_regex_benchmark.pl'),
            0500, 'dev/tools/phase36_regex_benchmark.pl'],
        [performance_schema => File::Spec->catfile($TOOL_DIR,
            'phase36_final_performance_schema.json'), 'final performance schema',
            File::Spec->catfile($root, 'phase36_final_performance_schema.json'),
            0400, 'dev/tools/phase36_final_performance_schema.json'],
        [performance_assembler => File::Spec->catfile($TOOL_DIR,
            'assemble_phase36_final_performance.pl'),
            'final performance assembler', File::Spec->catfile($root,
                'assemble_phase36_final_performance.pl'),
            0500, 'dev/tools/assemble_phase36_final_performance.pl'],
    );
    my %pinned;
    for my $input (@inputs) {
        my ($name, $source, $label, $target, $mode, $repository_path) = @$input;
        die "Validation policy input count exceeds bound\n"
            if $sealed->{copied_files} >= $MAX_PINNED_FILES;
        $source = absolute_regular_path($source, $label);
        my $record = snapshot_file($sealed, $source, $target, $label);
        chmod $mode, $target or die "Cannot set pinned $label mode: $!\n";
        $record->{identity} = [Time::HiRes::lstat($target)];
        $record->{label} = $label;
        $record->{repository_path} = $repository_path;
        if ($name eq 'requirements') {
            my $bytes = read_record_bounded($record, $MAX_JSON_BYTES, $label);
            retain_record_bytes($sealed, $record, $bytes, $label);
        }
        $pinned{$name} = $record;
    }
    if ($trusted) {
        for my $record (values %pinned) {
            my $bytes = trusted_git_output($trusted,
                $trusted->{candidate_source}, 'show',
                "$trusted->{expected_commit}:$record->{repository_path}");
            die "$record->{label} differs from expected candidate tree bytes\n"
                unless sha256_hex($bytes) eq $record->{sha256}
                    && length($bytes) == $record->{size};
        }
    }
    # Preserve the existing internal record key for callers that audit all
    # validation policy inputs.  It identifies the pinned verifier containing
    # the expected archive-call surface; it is not an executable archive tool.
    $pinned{jar} = $pinned{verifier};
    return $sealed->{inputs} = \%pinned;
}

our $INPUT_OBSERVER;
sub assert_pinned_input {
    my ($record) = @_;
    $INPUT_OBSERVER->($record) if $INPUT_OBSERVER;
    assert_snapshot_record($record, $record->{label});
}

sub existing_file {
    my ($path, $label) = @_;
    my $resolved = abs_path($path) or die "Cannot resolve $label $path\n";
    die "$label is missing or empty: $path\n" unless -f $resolved && -s $resolved;
    return $resolved;
}

sub require_hash {
    my ($value, $label) = @_;
    die "$label must be an object\n" unless ref($value) eq 'HASH';
    return $value;
}

sub require_sha {
    my ($value, $label) = @_;
    die "$label is missing or malformed\n"
        unless defined($value) && !ref($value) && $value =~ /\A[0-9a-f]{64}\z/;
    return $value;
}

sub number {
    my ($value) = @_;
    return defined($value) && !ref($value)
        && $value =~ /\A-?(?:\d+(?:\.\d*)?|\.\d+)\z/;
}

sub true_value {
    my ($value) = @_;
    return defined($value) && ($value eq '1' || $value eq 'true');
}

sub canonical {
    return JSON::PP->new->canonical->encode($_[0]);
}

sub sha256_file_streaming {
    my ($path) = @_;
    my $digest = Digest::SHA->new(256);
    sysopen my $fh, $path, O_RDONLY
        or die "Cannot hash $path: $!\n";
    binmode $fh, ':raw' or die "Cannot set raw hash mode for $path: $!\n";
    while (1) {
        my $count = sysread($fh, my $chunk, $STREAM_BUFFER);
        die "Cannot hash $path: $!\n" unless defined $count;
        last unless $count;
        $digest->add($chunk);
    }
    close $fh or die "Cannot close hashed file $path: $!\n";
    return $digest->hexdigest;
}

sub rewind_record {
    my ($record, $label) = @_;
    my $fh = $record->{consumer_fh}
        or die "$label has no pinned consumer descriptor\n";
    seek($fh, 0, 0) or die "Cannot rewind pinned $label: $!\n";
    return $fh;
}

sub sha256_record_streaming {
    my ($record) = @_;
    return sha256_hex(${$record->{retained_bytes}})
        if $record->{retained_bytes};
    my $fh = rewind_record($record, $record->{label} // $record->{snapshot});
    my $digest = Digest::SHA->new(256);
    while (1) {
        my $count = sysread($fh, my $chunk, $STREAM_BUFFER);
        die "Cannot hash pinned input: $!\n" unless defined $count;
        last unless $count;
        $digest->add($chunk);
    }
    return $digest->hexdigest;
}

sub read_record_bounded {
    my ($record, $limit, $label) = @_;
    die "$label exceeds $limit bytes\n" if $record->{size} > $limit;
    return ${$record->{retained_bytes}} if $record->{retained_bytes};
    my $fh = rewind_record($record, $label);
    my $contents = '';
    while (1) {
        my $count = sysread($fh, my $chunk, 64 * 1024);
        die "Cannot read pinned $label: $!\n" unless defined $count;
        last unless $count;
        $contents .= $chunk;
        die "$label grew beyond $limit bytes\n" if length($contents) > $limit;
    }
    return $contents;
}

sub retain_record_bytes {
    my ($sealed, $record, $bytes, $label) = @_;
    return if $record->{retained_bytes};
    my $total = ($sealed->{retained_bytes} // 0) + length($bytes);
    die "Retained validation metadata exceeds bounded limit at $label\n"
        if $total > $MAX_RETAINED_BYTES;
    $record->{retained_bytes} = \$bytes;
    $sealed->{retained_bytes} = $total;
}

sub load_json_bounded {
    my ($path, $label) = @_;
    return decode_json_object(
        read_raw_bounded($path, $MAX_JSON_BYTES), $label, $path);
}

sub decode_json_object {
    my ($bytes, $label, $path) = @_;
    my $document = eval { JSON::PP->new->utf8->decode($bytes) };
    die "Invalid $label JSON in $path\n"
        unless $document && ref($document) eq 'HASH';
    return $document;
}

sub decode_json_value {
    my ($bytes, $label, $path) = @_;
    my $document = eval { JSON::PP->new->utf8->decode($bytes) };
    die "Invalid $label JSON in $path\n" unless defined $document && ref($document);
    return $document;
}

sub read_raw_bounded {
    my ($path, $limit) = @_;
    my @st = stat($path);
    die "Cannot inspect bounded input $path: $!\n" unless @st;
    die "Bounded input exceeds $limit bytes: $path\n" if $st[7] > $limit;
    sysopen my $fh, $path, O_RDONLY or die "Cannot read $path: $!\n";
    binmode $fh, ':raw' or die "Cannot set raw read mode for $path: $!\n";
    my $contents = '';
    while (1) {
        my $count = sysread($fh, my $chunk, 64 * 1024);
        die "Cannot read $path: $!\n" unless defined $count;
        last unless $count;
        $contents .= $chunk;
        die "Bounded input grew beyond $limit bytes: $path\n"
            if length($contents) > $limit;
    }
    close $fh or die "Cannot close $path: $!\n";
    return $contents;
}

sub files_equal_streaming {
    my ($left, $right) = @_;
    my @left_stat = stat($left);
    my @right_stat = stat($right);
    return 0 unless @left_stat && @right_stat && $left_stat[7] == $right_stat[7];
    open my $lfh, '<:raw', $left or die "Cannot compare $left: $!\n";
    open my $rfh, '<:raw', $right or die "Cannot compare $right: $!\n";
    my $equal = 1;
    while (1) {
        my $lc = sysread($lfh, my $lb, $STREAM_BUFFER);
        my $rc = sysread($rfh, my $rb, $STREAM_BUFFER);
        die "Cannot stream-compare files: $!\n" unless defined($lc) && defined($rc);
        if ($lc != $rc || ($lc && $lb ne $rb)) { $equal = 0; last }
        last unless $lc;
    }
    close $lfh or die "Cannot close compared file $left: $!\n";
    close $rfh or die "Cannot close compared file $right: $!\n";
    return $equal;
}

sub publish_atomic {
    my ($path, $bytes) = @_;
    my $absolute = File::Spec->rel2abs($path);
    my $directory = dirname($absolute);
    die "Output directory does not exist: $directory\n" unless -d $directory;
    die "Refusing to overwrite output $path\n" if lstat($absolute);
    my $stage_dir = tempdir('phase36-stage-XXXXXXXX', DIR => $directory,
        CLEANUP => 0);
    my $temporary = File::Spec->catfile($stage_dir, 'report.tmp');
    my $ready = File::Spec->catfile($stage_dir, 'report.ready');
    my $fh;
    my ($published, $linked) = (0, 0);
    my $ok = eval {
        assert_atomic_link_capability($stage_dir);
        sysopen $fh, $temporary, O_RDWR | O_CREAT | O_EXCL, 0600
            or die "Cannot create temporary output $temporary: $!\n";
        binmode $fh, ':raw' or die "Cannot set raw output mode $temporary: $!\n";
        checked_print($fh, $bytes, $temporary);
        checked_flush($fh, $temporary);
        my @pinned = Time::HiRes::stat($fh);
        die "Cannot stat staged output descriptor: $!\n" unless @pinned;
        die "Staged output has the wrong byte length\n" unless $pinned[7] == length($bytes);
        checked_close($fh, $temporary);
        undef $fh;
        checked_rename($temporary, $ready);
        publication_observer('ready', $ready, $absolute, $fh);
        assert_staging_identity($ready, \@pinned, $bytes);
        checked_link($ready, $absolute);
        $linked = 1;
        publication_observer('linked', $ready, $absolute, $fh);
        assert_published_identity($absolute, \@pinned, $bytes);
        checked_unlink($ready, 'staged ready output');
        checked_rmdir($stage_dir, 'private staging directory');
        assert_published_identity($absolute, \@pinned, $bytes);
        $published = 1;
        1;
    };
    my $error = $@;
    if (!$ok) {
        CORE::close($fh) if defined $fh;
        my @cleanup_errors;
        if ($linked && lstat($absolute)) {
            unlink($absolute) or push @cleanup_errors,
                "cannot remove failed authoritative output $absolute: $!";
        }
        unlink($temporary) if lstat($temporary);
        unlink($ready) if lstat($ready);
        rmdir($stage_dir) if -d $stage_dir;
        die $error . (@cleanup_errors ? join("\n", @cleanup_errors) . "\n" : '');
    }
    return $published;
}

our $PUBLICATION_OBSERVER;
sub publication_observer {
    $PUBLICATION_OBSERVER->(@_) if $PUBLICATION_OBSERVER;
}

sub assert_atomic_link_capability {
    my ($directory) = @_;
    my $source = File::Spec->catfile($directory, 'link-capability-source');
    my $target = File::Spec->catfile($directory, 'link-capability-target');
    my $fh;
    my $ok = eval {
        sysopen $fh, $source, O_WRONLY | O_CREAT | O_EXCL, 0600
            or die "Cannot create hard-link capability probe: $!\n";
        binmode $fh, ':raw'
            or die "Cannot set hard-link capability probe raw mode: $!\n";
        print {$fh} "phase36-link-capability\n"
            or die "Cannot write hard-link capability probe: $!\n";
        $fh->flush or die "Cannot flush hard-link capability probe: $!\n";
        close $fh or die "Cannot close hard-link capability probe: $!\n";
        undef $fh;
        checked_link($source, $target);
        my @source_identity = Time::HiRes::lstat($source);
        my @target_identity = Time::HiRes::lstat($target);
        die "Filesystem hard links do not preserve file identity\n"
            unless @source_identity && @target_identity
                && same_file_identity(\@source_identity, \@target_identity);
        die "Filesystem hard links do not provide no-overwrite publication\n"
            if CORE::link($source, $target);
        CORE::unlink($target)
            or die "Cannot remove hard-link capability target: $!\n";
        CORE::unlink($source)
            or die "Cannot remove hard-link capability source: $!\n";
        1;
    };
    my $error = $@;
    if (!$ok) {
        CORE::close($fh) if defined $fh;
        CORE::unlink($target) if lstat($target);
        CORE::unlink($source) if lstat($source);
        die "Atomic no-overwrite publication is unsupported: $error";
    }
    return 1;
}

sub assert_staging_identity {
    my ($path, $pinned, $bytes) = @_;
    my @path = Time::HiRes::lstat($path);
    die "Staging pathname was replaced before publication\n"
        unless @path && !S_ISLNK($path[2]) && S_ISREG($path[2])
            && $path[0] == $pinned->[0] && $path[1] == $pinned->[1]
            && $path[2] == $pinned->[2] && $path[7] == $pinned->[7]
            && sha256_file_streaming($path) eq sha256_hex($bytes);
}

sub assert_published_identity {
    my ($path, $pinned, $bytes) = @_;
    my @path = Time::HiRes::lstat($path);
    die "Published output identity or bytes changed\n"
        unless @path && !S_ISLNK($path[2]) && S_ISREG($path[2])
            && $path[0] == $pinned->[0] && $path[1] == $pinned->[1]
            && $path[7] == length($bytes)
            && sha256_file_streaming($path) eq sha256_hex($bytes);
}

sub checked_print {
    my ($fh, $bytes, $label) = @_;
    print {$fh} $bytes or die "Cannot write $label: $!\n";
}

sub checked_flush {
    my ($fh, $label) = @_;
    $fh->flush or die "Cannot flush $label: $!\n";
}

sub checked_close {
    my ($fh, $label) = @_;
    close $fh or die "Cannot close $label: $!\n";
}

sub checked_rename {
    my ($from, $to) = @_;
    rename $from, $to or die "Cannot atomically publish $to: $!\n";
}

sub checked_link {
    my ($from, $to) = @_;
    link $from, $to or die "Cannot atomically publish $to without overwrite: $!\n";
}

sub checked_unlink {
    my ($path, $label) = @_;
    unlink $path or die "Cannot remove $label $path: $!\n";
}

sub checked_rmdir {
    my ($path, $label) = @_;
    rmdir $path or die "Cannot remove $label $path: $!\n";
}

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: check_phase36_release_manifest.pl --evidence FILE
       --expected-commit FULL_SHA --expected-parent FULL_SHA --output FILE
       --baseline-source DIR --candidate-source DIR --perl5-source DIR
       --baseline-jar FILE --candidate-jar FILE
       --baseline-launcher FILE --candidate-launcher FILE
       --interpreter-launcher FILE --java FILE --perl FILE
       --git FILE --ps FILE --uptime FILE --jfr-tool FILE --jfc FILE
       --time FILE --ordered-test-source FILE
       --ordered-fixture-manifest FILE --dbix-archive FILE
       --authority-key PRIVATE_FILE

Final, fail-closed Phase 36 release wrapper. It first requires the existing
acceptance checker to validate performance delegation, invokes the pinned final
performance checker exactly once in strict mode with wrapper-selected authority,
then independently verifies the sealed strict Joni fork notice.  Only the final
wrapper publishes the authoritative report, through exclusive publication.

Pinned checked-in checker and verifier source is not treated as general
untrusted Perl.  Its release invariant is that command-capable operations use
only the approved ordinary surface intercepted by this wrapper; explicit CORE
qualification of system, open, readpipe, or exec is rejected before source
compilation.
USAGE
    exit $status;
}

1;
