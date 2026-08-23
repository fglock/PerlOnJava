#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use Errno qw(EINTR);
use Fcntl qw(:DEFAULT :mode);
use File::Basename qw(dirname basename);
use File::Spec;
use File::Temp qw(tempdir);
use Getopt::Long qw(GetOptions);
use IO::Select;
use IO::Handle;
use IPC::Open3;
use JSON::PP;
use MIME::Base64 qw(encode_base64);
use Symbol qw(gensym);
use Time::HiRes qw(clock_gettime sleep CLOCK_MONOTONIC);

my $VERSION = '1.0.0';
my $MAX_RESPONSE = 4 * 1024 * 1024;
my $MAX_TOTAL = 24 * 1024 * 1024;
my $MAX_RECORDS = 100;
my $MAX_EXECUTABLE = 128 * 1024 * 1024;
my %EXECUTABLE_PIN;

my %option = (timeout => 900, poll_interval => 15, max_api_bytes => $MAX_RESPONSE);
my (@ubuntu, @windows);
my $help;
GetOptions(
    'source-dir=s' => \$option{source_dir},
    'git=s' => \$option{git},
    'gh=s' => \$option{gh},
    'offline-api-dir=s' => \$option{offline_api_dir},
    'expected-commit=s' => \$option{expected_commit},
    'repository=s' => \$option{repository},
    'workflow-id=i' => \$option{workflow_id},
    'workflow-name=s' => \$option{workflow_name},
    'workflow-file=s' => \$option{workflow_file},
    'ubuntu-check=s@' => \@ubuntu,
    'windows-check=s@' => \@windows,
    'timeout=i' => \$option{timeout},
    'poll-interval=i' => \$option{poll_interval},
    'max-api-bytes=i' => \$option{max_api_bytes},
    'output=s' => \$option{output},
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;

for my $key (qw(source_dir git expected_commit repository workflow_id
        workflow_name workflow_file output)) {
    die "--" . ($key =~ s/_/-/gr) . " is required\n"
        unless defined($option{$key}) && length($option{$key});
}
die "--gh is required without --offline-api-dir\n"
    unless defined($option{offline_api_dir}) || defined($option{gh});
die "At least one --ubuntu-check is required\n" unless @ubuntu;
die "At least one --windows-check is required\n" unless @windows;
die "Expected commit must be a full lowercase Git SHA\n"
    unless $option{expected_commit} =~ /\A[0-9a-f]{40}\z/;
die "Repository must be owner/name\n"
    unless $option{repository} =~ /\A[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\z/;
die "Workflow ID must be positive\n" unless $option{workflow_id} > 0;
die "Workflow file must be a repository-relative .github/workflows YAML path\n"
    unless $option{workflow_file} =~ m{\A\.github/workflows/[A-Za-z0-9_.-]+\.ya?ml\z};
die "Timeout must be between 1 and 3600 seconds\n"
    unless $option{timeout} >= 1 && $option{timeout} <= 3600;
die "Poll interval must be between 1 and 300 seconds\n"
    unless $option{poll_interval} >= 1 && $option{poll_interval} <= 300;
die "API response limit must be between 1024 and $MAX_RESPONSE bytes\n"
    unless $option{max_api_bytes} >= 1024 && $option{max_api_bytes} <= $MAX_RESPONSE;
for my $name (@ubuntu, @windows) {
    die "Required check names must be printable non-empty strings\n"
        unless length($name) <= 200 && $name =~ /\A[\x20-\x7e]+\z/;
}
my %seen_name;
die "Required check names must be unique\n"
    if grep { $seen_name{$_}++ } (@ubuntu, @windows);

my $source = canonical_directory($option{source_dir}, 'source directory');
my $git = trusted_executable($option{git}, 'Git executable');
my $offline = defined($option{offline_api_dir})
    ? canonical_directory($option{offline_api_dir}, 'offline API directory') : undef;
my $gh = defined($option{gh}) ? trusted_executable($option{gh}, 'GitHub CLI') : undef;
my $workflow_path = contained_existing_file($source, $option{workflow_file},
    'workflow file', $option{max_api_bytes});
my $output = validate_output_path($option{output});

my @raw_evidence;
my $git_version = command_bytes([$git, '--version'], 15, 64 * 1024, 'git-version');
push @raw_evidence, raw_record('tool:git-version', $git_version);
my $gh_version;
if ($gh) {
    $gh_version = command_bytes([$gh, '--version'], 15, 64 * 1024, 'gh-version');
    push @raw_evidence, raw_record('tool:gh-version', $gh_version);
} else {
    $gh_version = "offline API fixtures (gh not invoked)\n";
    push @raw_evidence, raw_record('tool:gh-version', $gh_version);
}

my $top = trim(command_bytes([$git, '-C', $source, 'rev-parse', '--show-toplevel'],
    15, 64 * 1024, 'git-root'));
die "Git top-level does not equal canonical source directory\n"
    unless canonical_directory($top, 'Git top-level') eq $source;
my $head = trim(command_bytes([$git, '-C', $source, 'rev-parse', 'HEAD'],
    15, 64 * 1024, 'git-head'));
die "Local HEAD is not exact expected commit: $head\n"
    unless $head eq $option{expected_commit};
my $status = command_bytes([$git, '-C', $source, 'status', '--porcelain=v1',
    '--untracked-files=all'], 30, 1024 * 1024, 'git-status');
die "Local source tree is not clean\n" if length($status);
my $tracked = trim(command_bytes([$git, '-C', $source, 'ls-files',
    '--error-unmatch', '--', $option{workflow_file}], 15, 64 * 1024,
    'git-workflow-tracked'));
die "Workflow file is not tracked at the expected path\n"
    unless $tracked eq $option{workflow_file};
my $committed_workflow = command_bytes([$git, '-C', $source, 'show',
    "$option{expected_commit}:$option{workflow_file}"], 30,
    $option{max_api_bytes}, 'git-workflow-bytes');
my $workflow_bytes = read_bounded($workflow_path, $option{max_api_bytes});
die "Workflow working bytes differ from the expected commit\n"
    unless $workflow_bytes eq $committed_workflow;

my $fetch = make_fetcher(\%option, $offline, $gh, \@raw_evidence);
my $repo = $option{repository};
my $workflow_endpoint = "repos/$repo/actions/workflows/$option{workflow_id}";
my $commit_endpoint = "repos/$repo/commits/$option{expected_commit}";
my $runs_endpoint = "repos/$repo/actions/workflows/$option{workflow_id}/runs"
    . "?head_sha=$option{expected_commit}&per_page=$MAX_RECORDS";
my $checks_endpoint = "repos/$repo/commits/$option{expected_commit}/check-runs"
    . "?per_page=$MAX_RECORDS";

my $workflow = decode_object($fetch->('workflow', $workflow_endpoint), 'workflow');
die "Wrong workflow ID\n" unless integer($workflow->{id}) == $option{workflow_id};
die "Wrong workflow name\n" unless scalar_string($workflow->{name}) eq $option{workflow_name};
die "Wrong workflow path\n" unless scalar_string($workflow->{path}) eq $option{workflow_file};
die "Workflow is not active\n" unless scalar_string($workflow->{state}) eq 'active';

my $commit = decode_object($fetch->('commit', $commit_endpoint), 'commit');
die "Wrong commit SHA from GitHub\n"
    unless scalar_string($commit->{sha}) eq $option{expected_commit};
my $commit_time = nested_string($commit, qw(commit committer date));
require_time($commit_time, 'commit timestamp');

my ($run, $runs_doc);
my $deadline = clock_gettime(CLOCK_MONOTONIC) + $option{timeout};
my $poll = 0;
while (1) {
    $poll++;
    my $raw = $fetch->("runs-$poll", $runs_endpoint, $poll);
    $runs_doc = decode_object($raw, 'workflow runs');
    my $runs = bounded_array($runs_doc->{workflow_runs}, 'workflow_runs');
    die "Incomplete workflow run response requires pagination\n"
        unless integer($runs_doc->{total_count}) == @$runs;
    die "Too many workflow runs for an unambiguous decision\n" if @$runs > $MAX_RECORDS;
    validate_run_scope($_, \%option) for @$runs;
    die "Ambiguous workflow runs for exact SHA\n" if @$runs > 1;
    if (@$runs) {
        $run = $runs->[0];
        die "Rerun attempts are not acceptable\n"
            unless integer($run->{run_attempt}) == 1;
        my $status_value = scalar_string($run->{status});
        if ($status_value eq 'completed') {
            die "Workflow run conclusion is not success: "
                . scalar_string($run->{conclusion}) . "\n"
                unless scalar_string($run->{conclusion}) eq 'success';
            last;
        }
        die "Workflow run has invalid non-terminal status: $status_value\n"
            unless $status_value =~ /\A(?:queued|in_progress|pending|requested|waiting)\z/;
    }
    if ($offline) {
        die(@$runs ? "Workflow run is still in-progress\n"
                   : "Workflow run is missing for exact SHA\n")
            unless $fetch->('offline-has-next-runs', '', $poll + 1);
        next;
    }
    my $remaining = $deadline - clock_gettime(CLOCK_MONOTONIC);
    die(@$runs ? "Workflow run remained in-progress until timeout\n"
               : "Workflow run is missing for exact SHA at timeout\n")
        if $remaining <= 0;
    sleep($remaining < $option{poll_interval} ? $remaining : $option{poll_interval});
}

my $run_id = integer($run->{id});
die "Workflow run ID must be positive\n" unless $run_id > 0;
my $suite_id = integer($run->{check_suite_id});
die "Workflow run check-suite ID must be positive\n" unless $suite_id > 0;
my $created = scalar_string($run->{created_at});
my $updated = scalar_string($run->{updated_at});
require_time($created, 'run created_at');
require_time($updated, 'run updated_at');
die "Stale workflow run predates the candidate commit\n" if $created lt $commit_time;
die "Stale workflow run has an inverted update interval\n" if $updated lt $created;

my $jobs_endpoint = "repos/$repo/actions/runs/$run_id/attempts/1/jobs?per_page=$MAX_RECORDS";
my $jobs_doc = decode_object($fetch->('jobs', $jobs_endpoint), 'workflow jobs');
my $jobs = bounded_array($jobs_doc->{jobs}, 'jobs');
die "Incomplete jobs response requires pagination\n"
    unless integer($jobs_doc->{total_count}) == @$jobs;
die "Workflow jobs are missing\n" unless @$jobs;
for my $job (@$jobs) {
    die "Job is not an object\n" unless ref($job) eq 'HASH';
    die "Job belongs to wrong run\n" unless integer($job->{run_id}) == $run_id;
    die "Job belongs to a rerun attempt\n" unless integer($job->{run_attempt}) == 1;
    die "Job has wrong SHA\n" unless scalar_string($job->{head_sha}) eq $option{expected_commit};
    die "Job is not completed\n" unless scalar_string($job->{status}) eq 'completed';
    die "Job conclusion is not success: " . scalar_string($job->{name}) . "="
        . scalar_string($job->{conclusion}) . "\n"
        unless scalar_string($job->{conclusion}) eq 'success';
}

my $checks_doc = decode_object($fetch->('checks', $checks_endpoint), 'check runs');
my $checks = bounded_array($checks_doc->{check_runs}, 'check_runs');
die "Incomplete check-runs response requires pagination\n"
    unless integer($checks_doc->{total_count}) == @$checks;
for my $check (@$checks) {
    die "Check run is not an object\n" unless ref($check) eq 'HASH';
}

my @required = (@ubuntu, @windows);
my (@sealed_jobs, @sealed_checks);
for my $name (@required) {
    my @named_jobs = grep { scalar_string($_->{name}) eq $name } @$jobs;
    die "Missing required workflow job: $name\n" unless @named_jobs;
    die "Ambiguous required workflow job: $name\n" unless @named_jobs == 1;
    my @named_checks = grep { scalar_string($_->{name}) eq $name } @$checks;
    die "Missing required check run: $name\n" unless @named_checks;
    die "Ambiguous required check run: $name\n" unless @named_checks == 1;
    my ($job, $check) = ($named_jobs[0], $named_checks[0]);
    die "Check run has wrong SHA: $name\n"
        unless scalar_string($check->{head_sha}) eq $option{expected_commit};
    die "Check run is not completed: $name\n"
        unless scalar_string($check->{status}) eq 'completed';
    die "Check run conclusion is not success: $name="
        . scalar_string($check->{conclusion}) . "\n"
        unless scalar_string($check->{conclusion}) eq 'success';
    die "Stale check run belongs to wrong check suite: $name\n"
        unless nested_integer($check, qw(check_suite id)) == $suite_id;
    die "Required check is not from github-actions: $name\n"
        unless nested_string($check, qw(app slug)) eq 'github-actions';
    die "Job/check ID mismatch for $name\n"
        unless integer($job->{id}) == integer($check->{id});
    push @sealed_jobs, seal_job($job);
    push @sealed_checks, seal_check($check);
}

my $payload = {
    schema => 'perlonjava.phase36.ci-acceptance-evidence/v1',
    producer => {name => 'run_phase36_ci_evidence.pl', version => $VERSION,
        sha256 => sha256_hex(read_bounded(abs_path($0), $MAX_RESPONSE))},
    verified => JSON::PP::true,
    authority => {
        repository => $repo,
        source_commit => $option{expected_commit},
        local_clean_exact_commit => JSON::PP::true,
        workflow => {id => 0 + $option{workflow_id}, name => $option{workflow_name},
            path => $option{workflow_file}, sha256 => sha256_hex($workflow_bytes),
            size => length($workflow_bytes)},
        run => seal_run($run),
        required_matrix => {ubuntu => \@ubuntu, windows => \@windows},
        jobs => \@sealed_jobs,
        checks => \@sealed_checks,
    },
    tools => {
        git => {path => $git, sha256 => $EXECUTABLE_PIN{$git}{sha256},
            size => $EXECUTABLE_PIN{$git}{size},
            version_sha256 => sha256_hex($git_version),
            version => trim($git_version)},
        gh => {path => ($gh // ''),
            ($gh ? (sha256 => $EXECUTABLE_PIN{$gh}{sha256},
                    size => $EXECUTABLE_PIN{$gh}{size}) : ()),
            version_sha256 => sha256_hex($gh_version),
            version => trim($gh_version), offline => $offline ? JSON::PP::true : JSON::PP::false},
    },
    raw_api_evidence => \@raw_evidence,
};
my $canonical = JSON::PP->new->utf8->canonical;
my $payload_bytes = $canonical->encode($payload);
my $artifact = {%$payload, seal => {algorithm => 'sha256',
    payload_sha256 => sha256_hex($payload_bytes)}};
my $rendered = $canonical->pretty->encode($artifact);
die "Final evidence artifact exceeds bounded size\n" if length($rendered) > $MAX_TOTAL * 2;
publish_atomic($output, $rendered);
print "$output\n";
exit 0;

sub make_fetcher {
    my ($options, $fixture_dir, $gh_path, $records) = @_;
    my $total = 0;
    my @run_files = $fixture_dir ? fixture_run_files($fixture_dir) : ();
    return sub {
        my ($label, $endpoint, $sequence) = @_;
        if ($label eq 'offline-has-next-runs') {
            return $sequence <= @run_files;
        }
        my $raw;
        if ($fixture_dir) {
            my $name = $label =~ /\Aruns-/ ? $run_files[$sequence - 1]
                : {workflow => 'workflow.json', commit => 'commit.json',
                   jobs => 'jobs.json', checks => 'checks.json'}->{$label};
            die "No offline fixture mapping for $label\n" unless defined $name;
            my $path = contained_existing_file($fixture_dir, $name,
                "offline $label fixture", $options->{max_api_bytes});
            $raw = read_bounded($path, $options->{max_api_bytes});
        } else {
            $raw = command_bytes([$gh_path, 'api', '--method', 'GET',
                '-H', 'Accept: application/vnd.github+json',
                '-H', 'X-GitHub-Api-Version: 2022-11-28', $endpoint],
                $options->{timeout}, $options->{max_api_bytes}, "gh-$label");
        }
        $total += length($raw);
        die "Total raw API evidence exceeds bounded size\n" if $total > $MAX_TOTAL;
        push @$records, raw_record("api:$label", $raw, $endpoint);
        return $raw;
    };
}

sub fixture_run_files {
    my ($dir) = @_;
    opendir my $dh, $dir or die "Cannot read offline API directory: $!\n";
    my @files = sort grep { /\Aruns-[0-9]{3}\.json\z/ } readdir $dh;
    closedir $dh or die "Cannot close offline API directory: $!\n";
    die "Offline API directory has no runs-NNN.json fixture\n" unless @files;
    die "Too many offline polling fixtures\n" if @files > $MAX_RECORDS;
    for my $index (0 .. $#files) {
        my $expected = sprintf('runs-%03d.json', $index + 1);
        die "Offline polling fixtures must be contiguous from runs-001.json\n"
            unless $files[$index] eq $expected;
    }
    return @files;
}

sub validate_run_scope {
    my ($run, $options) = @_;
    die "Workflow run is not an object\n" unless ref($run) eq 'HASH';
    die "Wrong-SHA workflow run returned\n"
        unless scalar_string($run->{head_sha}) eq $options->{expected_commit};
    die "Wrong-repository workflow run returned\n"
        unless nested_string($run, qw(repository full_name)) eq $options->{repository};
    die "Wrong-workflow run returned\n"
        unless integer($run->{workflow_id}) == $options->{workflow_id}
            && scalar_string($run->{path}) eq $options->{workflow_file};
}

sub seal_run {
    my ($run) = @_;
    return {map { $_ => $run->{$_} } qw(id run_number run_attempt workflow_id
        check_suite_id head_sha event status conclusion created_at updated_at)};
}
sub seal_job {
    my ($job) = @_;
    return {map { $_ => $job->{$_} } qw(id run_id run_attempt name head_sha
        status conclusion started_at completed_at)};
}
sub seal_check {
    my ($check) = @_;
    return {(map { $_ => $check->{$_} } qw(id name head_sha status conclusion
        started_at completed_at)), check_suite_id => nested_integer($check, qw(check_suite id)),
        app => {id => nested_integer($check, qw(app id)),
                slug => nested_string($check, qw(app slug))}};
}

sub decode_object {
    my ($raw, $label) = @_;
    my $value = eval { JSON::PP->new->utf8->decode($raw) };
    die "Invalid $label JSON: $@" if $@;
    die "$label JSON root is not an object\n" unless ref($value) eq 'HASH';
    my $nodes = 0;
    inspect_json($value, 0, \$nodes, $label);
    return $value;
}
sub inspect_json {
    my ($value, $depth, $nodes, $label) = @_;
    die "$label JSON nesting is too deep\n" if $depth > 32;
    die "$label JSON has too many values\n" if ++$$nodes > 50_000;
    if (ref($value) eq 'ARRAY') {
        die "$label JSON has an oversized array\n" if @$value > 10_000;
        inspect_json($_, $depth + 1, $nodes, $label) for @$value;
    } elsif (ref($value) eq 'HASH') {
        die "$label JSON has too many object fields\n" if keys(%$value) > 1_000;
        inspect_json($_, $depth + 1, $nodes, $label) for values %$value;
    } elsif (ref($value) && ref($value) !~ /Boolean/) {
        die "$label JSON contains an unsupported value\n";
    }
}
sub bounded_array {
    my ($value, $label) = @_;
    die "$label is not an array\n" unless ref($value) eq 'ARRAY';
    die "$label exceeds $MAX_RECORDS records\n" if @$value > $MAX_RECORDS;
    return $value;
}
sub integer {
    my ($value) = @_;
    die "Expected an integer API field\n"
        if !defined($value) || ref($value) || $value !~ /\A[0-9]+\z/;
    return 0 + $value;
}
sub scalar_string {
    my ($value) = @_;
    die "Expected a scalar string API field\n" if !defined($value) || ref($value);
    return "$value";
}
sub nested_string {
    my ($value, @path) = @_;
    for my $key (@path) {
        die "Missing nested API field " . join('.', @path) . "\n"
            unless ref($value) eq 'HASH' && exists $value->{$key};
        $value = $value->{$key};
    }
    return scalar_string($value);
}
sub nested_integer {
    my ($value, @path) = @_;
    for my $key (@path) {
        die "Missing nested API field " . join('.', @path) . "\n"
            unless ref($value) eq 'HASH' && exists $value->{$key};
        $value = $value->{$key};
    }
    return integer($value);
}
sub require_time {
    my ($value, $label) = @_;
    die "$label is not a canonical UTC timestamp\n"
        unless $value =~ /\A\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ\z/;
}

sub raw_record {
    my ($label, $bytes, $endpoint) = @_;
    my $record = {label => $label, size => length($bytes),
        sha256 => sha256_hex($bytes), base64 => encode_base64($bytes, '')};
    $record->{endpoint} = $endpoint if defined $endpoint;
    return $record;
}
sub trim { my $v = $_[0]; $v =~ s/\s+\z//; return $v }

sub command_bytes {
    my ($argv, $timeout, $limit, $label) = @_;
    die "Empty command for $label\n" unless ref($argv) eq 'ARRAY' && @$argv;
    my $err = gensym;
    my ($out, $pid);
    assert_executable_identity($argv->[0], 'immediately before execution');
    $pid = eval { open3(undef, $out, $err, @$argv) };
    die "Cannot start $label: $@" if $@;
    binmode $out, ':raw';
    binmode $err, ':raw';
    my $select = IO::Select->new($out, $err);
    my ($stdout, $stderr) = ('', '');
    my $deadline = clock_gettime(CLOCK_MONOTONIC) + $timeout;
    while ($select->count) {
        my $remaining = $deadline - clock_gettime(CLOCK_MONOTONIC);
        if ($remaining <= 0) {
            kill 'TERM', $pid;
            waitpid($pid, 0);
            assert_executable_identity($argv->[0], 'immediately after execution');
            die "$label timed out after $timeout seconds\n";
        }
        my @ready = $select->can_read($remaining);
        next unless @ready;
        for my $fh (@ready) {
            my $count = sysread($fh, my $chunk, 64 * 1024);
            next if !defined($count) && $! == EINTR;
            die "Cannot read $label output: $!\n" unless defined $count;
            if (!$count) { $select->remove($fh); close $fh; next }
            if (fileno($fh) == fileno($out)) { $stdout .= $chunk }
            else { $stderr .= $chunk }
            if (length($stdout) > $limit || length($stderr) > $limit) {
                kill 'TERM', $pid;
                waitpid($pid, 0);
                assert_executable_identity($argv->[0], 'immediately after execution');
                die "$label output exceeds bounded size\n";
            }
        }
    }
    waitpid($pid, 0);
    my $status = $?;
    assert_executable_identity($argv->[0], 'immediately after execution');
    die "$label failed with status " . ($status >> 8) . ": $stderr"
        if $status != 0;
    return $stdout;
}

sub trusted_executable {
    my ($path, $label) = @_;
    die "$label path must be absolute\n" unless File::Spec->file_name_is_absolute($path);
    my @stat = lstat($path);
    die "$label is not a regular non-symlink executable\n"
        unless @stat && !S_ISLNK($stat[2]) && S_ISREG($stat[2]) && -x $path;
    my $canonical = abs_path($path);
    $EXECUTABLE_PIN{$canonical} = snapshot_executable($canonical, $label);
    return $canonical;
}
sub snapshot_executable {
    my ($path, $label) = @_;
    my @before = lstat($path);
    die "$label exceeds the executable snapshot limit\n"
        unless @before && $before[7] <= $MAX_EXECUTABLE;
    my $sha = sha256_file_streaming($path, $MAX_EXECUTABLE);
    my @after = lstat($path);
    die "$label changed while its bytes were being snapshotted\n"
        unless same_identity(\@before, \@after);
    return {device => $before[0], inode => $before[1], mode => $before[2],
        size => $before[7], mtime => $before[9], sha256 => $sha};
}
sub assert_executable_identity {
    my ($path, $when) = @_;
    my $pin = $EXECUTABLE_PIN{$path}
        or die "Executable was not snapshotted before use: $path\n";
    my @now = lstat($path);
    die "Trusted executable identity changed $when: $path\n"
        unless @now && !S_ISLNK($now[2]) && S_ISREG($now[2])
            && $now[0] == $pin->{device} && $now[1] == $pin->{inode}
            && $now[2] == $pin->{mode} && $now[7] == $pin->{size}
            && $now[9] == $pin->{mtime}
            && sha256_file_streaming($path, $MAX_EXECUTABLE) eq $pin->{sha256};
}
sub same_identity {
    my ($left, $right) = @_;
    return @$left && @$right && $left->[0] == $right->[0]
        && $left->[1] == $right->[1] && $left->[2] == $right->[2]
        && $left->[7] == $right->[7] && $left->[9] == $right->[9];
}
sub sha256_file_streaming {
    my ($path, $limit) = @_;
    open my $fh, '<:raw', $path or die "Cannot hash $path: $!\n";
    my $digest = Digest::SHA->new(256);
    my $total = 0;
    while (1) {
        my $count = sysread($fh, my $chunk, 64 * 1024);
        die "Cannot hash $path: $!\n" unless defined $count;
        last unless $count;
        $total += $count;
        die "Executable exceeds snapshot limit while hashing: $path\n"
            if $total > $limit;
        $digest->add($chunk);
    }
    close $fh or die "Cannot close hashed executable $path: $!\n";
    return $digest->hexdigest;
}
sub canonical_directory {
    my ($path, $label) = @_;
    my $absolute = abs_path($path);
    die "$label does not exist\n" unless defined($absolute) && -d $absolute;
    die "$label path is not canonical\n" unless File::Spec->rel2abs($path) eq $absolute;
    return $absolute;
}
sub contained_existing_file {
    my ($root, $relative, $label, $limit) = @_;
    die "$label path is not a safe relative path\n"
        if File::Spec->file_name_is_absolute($relative) || $relative =~ m{(?:\A|/)\.\.(?:/|\z)};
    my $candidate = File::Spec->catfile($root, split m{/}, $relative);
    my @stat = lstat($candidate);
    die "$label is not a regular non-symlink file\n"
        unless @stat && !S_ISLNK($stat[2]) && S_ISREG($stat[2]);
    die "$label exceeds bounded size\n" if $stat[7] > $limit;
    my $canonical = abs_path($candidate);
    die "$label escapes its trusted directory\n"
        unless defined($canonical) && index($canonical, "$root/") == 0;
    return $canonical;
}
sub validate_output_path {
    my ($path) = @_;
    die "Output path must be absolute\n" unless File::Spec->file_name_is_absolute($path);
    die "Output filename is invalid\n" if basename($path) =~ /\A(?:\.|\.\.)\z/;
    my $parent = canonical_directory(dirname($path), 'output directory');
    my $canonical = File::Spec->catfile($parent, basename($path));
    die "Output path is not canonical\n" unless $canonical eq $path;
    die "Refusing to overwrite output $path\n" if lstat($path);
    return $path;
}
sub read_bounded {
    my ($path, $limit) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $bytes = '';
    while (1) {
        my $count = sysread($fh, my $chunk, 64 * 1024);
        die "Cannot read $path: $!\n" unless defined $count;
        last unless $count;
        $bytes .= $chunk;
        die "Bounded input exceeds $limit bytes: $path\n" if length($bytes) > $limit;
    }
    close $fh or die "Cannot close $path: $!\n";
    return $bytes;
}
sub publish_atomic {
    my ($path, $bytes) = @_;
    my $dir = dirname($path);
    die "Refusing to overwrite output $path\n" if lstat($path);
    my $stage = tempdir('phase36-ci-stage-XXXXXXXX', DIR => $dir, CLEANUP => 0);
    chmod 0700, $stage or die "Cannot protect staging directory: $!\n";
    my $ready = File::Spec->catfile($stage, 'evidence.ready');
    my $fh;
    my $linked = 0;
    my $ok = eval {
        sysopen $fh, $ready, O_WRONLY | O_CREAT | O_EXCL, 0600
            or die "Cannot create staged evidence: $!\n";
        binmode $fh, ':raw';
        print {$fh} $bytes or die "Cannot write staged evidence: $!\n";
        $fh->sync or die "Cannot sync staged evidence: $!\n";
        close $fh or die "Cannot close staged evidence: $!\n";
        undef $fh;
        link $ready, $path or die "Cannot atomically publish without overwrite: $!\n";
        $linked = 1;
        die "Published evidence hash mismatch\n"
            unless sha256_hex(read_bounded($path, length($bytes) + 1)) eq sha256_hex($bytes);
        unlink $ready or die "Cannot remove staged evidence: $!\n";
        rmdir $stage or die "Cannot remove staging directory: $!\n";
        1;
    };
    my $error = $@;
    if (!$ok) {
        close $fh if defined $fh;
        unlink $path if $linked && lstat($path);
        unlink $ready if lstat($ready);
        rmdir $stage if -d $stage;
        die $error;
    }
}

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: run_phase36_ci_evidence.pl --source-dir ABS --git ABS [--gh ABS]
  --expected-commit SHA --repository OWNER/REPO --workflow-id ID
  --workflow-name NAME --workflow-file .github/workflows/FILE.yml
  --ubuntu-check NAME --windows-check NAME --output ABS
  [--offline-api-dir ABS] [--timeout SEC] [--poll-interval SEC]
USAGE
    exit $status;
}
