#!/usr/bin/env perl

use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use Fcntl qw(O_CREAT O_EXCL O_WRONLY);
use File::Spec;
use FindBin qw($Bin);
use Getopt::Long qw(GetOptions);
use IO::Select;
use JSON::PP;
use POSIX qw(WNOHANG);
use Time::HiRes qw(sleep time);

my %option = (
    samples => 5,
    timeout => 300,
    benchmark => File::Spec->catfile($Bin, 'phase36_regex_benchmark.pl'),
);
my $help;
GetOptions(
    'baseline-source=s' => \$option{baseline_source},
    'candidate-source=s' => \$option{candidate_source},
    'baseline-jar=s' => \$option{baseline_jar},
    'candidate-jar=s' => \$option{candidate_jar},
    'baseline-launcher=s' => \$option{baseline_launcher},
    'candidate-launcher=s' => \$option{candidate_launcher},
    'java=s' => \$option{java},
    'git=s' => \$option{git},
    'benchmark=s' => \$option{benchmark},
    'evidence-dir=s' => \$option{evidence_dir},
    'output=s' => \$option{output},
    'samples=s' => \$option{samples},
    'timeout=s' => \$option{timeout},
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;

for my $required (qw(baseline_source candidate_source baseline_jar candidate_jar
        baseline_launcher candidate_launcher evidence_dir)) {
    die "--$required is required\n"
        unless defined $option{$required} && length $option{$required};
}
die "--java is required\n" unless defined($option{java}) && length($option{java});
die "--git is required\n" unless defined($option{git}) && length($option{git});
die "ordinary performance process-tree contract is Unix-only; A232 must validate a native Windows tree strategy\n"
    if $^O eq 'MSWin32';
die "--samples must be an integer between 5 and 100\n"
    unless bounded_positive_number($option{samples}, 100, 1)
        && $option{samples} >= 5;
die "--timeout must be an integer between 1 and 7200 seconds\n"
    unless bounded_positive_number($option{timeout}, 7200, 1);

my $evidence = private_empty_directory($option{evidence_dir});
$option{git} = file_identity($option{git}, 'Git executable', 1)->{path};
my $git_sha256 = sha256_file($option{git});
my %closed_environment = (
    PATH => '', LANG => 'C', LC_ALL => 'C', TZ => 'UTC',
    HOME => $evidence, PERLONJAVA_HOME => $evidence, TMPDIR => $evidence,
);
$option{output} //= File::Spec->catfile($evidence, 'performance.json');
my $output = absolute_output($option{output});
die "Output must be inside the evidence directory\n"
    unless path_is_inside($output, $evidence);
die "Refusing to overwrite output $output\n" if -e $output;

my %source = (
    baseline => source_identity($option{baseline_source}, 'baseline'),
    candidate => source_identity($option{candidate_source}, 'candidate'),
);
die "Candidate source parent is not the exact baseline source commit\n"
    unless $source{candidate}{parent_commit} eq $source{baseline}{commit};

my %side;
for my $name (qw(baseline candidate)) {
    my $jar_key = "${name}_jar";
    my $launcher_key = "${name}_launcher";
    $side{$name} = {
        source => $source{$name},
        jar => file_identity($option{$jar_key}, "$name JAR", 0),
        launcher => file_identity($option{$launcher_key}, "$name launcher", 1),
    };
}
my $benchmark = file_identity($option{benchmark}, 'benchmark', 0);
my $java = file_identity($option{java}, 'Java executable', 1);
die "PERLONJAVA_JAVA_BIN must equal the authority-selected --java path\n"
    unless defined($ENV{PERLONJAVA_JAVA_BIN})
        && abs_path($ENV{PERLONJAVA_JAVA_BIN}) eq $java->{path};
$closed_environment{PERLONJAVA_JAVA_BIN} = $java->{path};

my $initial = immutable_signature(\%source, \%side, $benchmark, $java);
my (@order, %metrics, %raw_logs);
for my $name (qw(baseline candidate)) {
    my $log = File::Spec->catfile($evidence, "identity-$name.log");
    probe_executable_identity($name, $log, $side{$name}, $option{timeout}, $java);
    push @{$raw_logs{$name}}, artifact($log);
}
for my $name (qw(baseline candidate)) {
    my $log = File::Spec->catfile($evidence, "warmup-$name.log");
    my $metric = execute_sample($name, 'warmup', $log, $side{$name}, $benchmark,
        $option{timeout}, $java);
    push @{$metrics{$name}{warmup}}, $metric;
    push @{$raw_logs{$name}}, artifact($log);
}
for my $sample (1 .. $option{samples}) {
    for my $name (qw(baseline candidate)) {
        my $label = sprintf('sample-%02d-%s', $sample, $name);
        my $log = File::Spec->catfile($evidence, "$label.log");
        my $metric = execute_sample($name, $label, $log, $side{$name}, $benchmark,
            $option{timeout}, $java);
        push @{$metrics{$name}{samples}}, $metric;
        push @{$raw_logs{$name}}, artifact($log);
        push @order, $name;
    }
}

my $final = immutable_signature(\%source, \%side, $benchmark, $java);
die "Performance input mutated during execution\n" unless $final eq $initial;

my @all_metrics = map { @{$metrics{$_}{warmup}}, @{$metrics{$_}{samples}} }
    qw(baseline candidate);
my %checksums = map { $_->{checksum} => 1 } @all_metrics;
die "Benchmark semantic checksum mismatch\n" unless keys(%checksums) == 1;
my $checksum = (keys %checksums)[0];

my @baseline_seconds = map { 0 + $_->{elapsed_seconds} }
    @{$metrics{baseline}{samples}};
my @candidate_seconds = map { 0 + $_->{elapsed_seconds} }
    @{$metrics{candidate}{samples}};
my $baseline_median = median(\@baseline_seconds);
my $candidate_median = median(\@candidate_seconds);
die sprintf("Candidate median regressed: %.9f > %.9f\n",
        $candidate_median, $baseline_median)
    if $candidate_median > $baseline_median;

my $document = {
    schema_version => 1,
    kind => 'performance',
    verified => JSON::PP::true,
    alternating_order => JSON::PP::true,
    sample_count_per_side => 0 + $option{samples},
    timeout_seconds => 0 + $option{timeout},
    baseline_seconds => \@baseline_seconds,
    candidate_seconds => \@candidate_seconds,
    baseline_throughput => [map { 0 + $_->{throughput} }
        @{$metrics{baseline}{samples}}],
    candidate_throughput => [map { 0 + $_->{throughput} }
        @{$metrics{candidate}{samples}}],
    baseline_median_seconds => $baseline_median,
    candidate_median_seconds => $candidate_median,
    semantic_checksum => $checksum,
    execution_order => \@order,
    source => { map { $_ => $source{$_} } qw(baseline candidate) },
    artifacts => {
        benchmark => $benchmark,
        baseline_jar => $side{baseline}{jar},
        candidate_jar => $side{candidate}{jar},
        baseline_launcher => $side{baseline}{launcher},
        candidate_launcher => $side{candidate}{launcher},
        java => $java,
        raw_logs => \%raw_logs,
    },
};
write_json_exclusive($output, $document);
print "$output\n";

sub probe_executable_identity {
    my ($side_name, $log, $identity, $timeout, $java) = @_;
    verify_execution_inputs("$side_name identity probe", $identity, undef, $java);
    die "Refusing to overwrite identity log $log\n" if -e $log;
    pipe my $reader, my $writer or die "Cannot create bounded output pipe: $!\n";
    my $pid = fork();
    die "Cannot fork $side_name identity probe: $!\n" unless defined $pid;
    if ($pid == 0) {
        close $reader;
        eval { POSIX::setpgid(0, 0) };
        open STDOUT, '>&', $writer or die "Cannot redirect output: $!\n";
        open STDERR, '>&', STDOUT or die "Cannot redirect STDERR: $!\n";
        close $writer;
        %ENV = (%closed_environment, PERLONJAVA_JAR => $identity->{jar}{path});
        exec { $identity->{launcher}{path} } $identity->{launcher}{path}, '-v';
        die "Cannot exec $identity->{launcher}{path}: $!\n";
    }
    close $writer;
    my $has_process_group = establish_process_group($pid);
    my $status = collect_bounded($pid, "$side_name identity probe", $log,
        $timeout, $has_process_group, $reader, 1024 * 1024);
    verify_execution_inputs("$side_name identity probe", $identity, undef, $java);
    validate_status($status, "$side_name identity probe", $log);
    my $text = read_raw($log);
    my %sha = map { $_ => 1 } ($text =~ /\b([0-9a-f]{7,40})\b/g);
    die "$side_name launcher did not report exactly one source commit; raw log: $log\n"
        unless keys(%sha) == 1;
    my ($reported) = keys %sha;
    die "$side_name launcher reported wrong executable identity $reported\n"
        unless index($identity->{source}{commit}, $reported) == 0;
}

sub execute_sample {
    my ($side_name, $label, $log, $identity, $benchmark_identity, $timeout,
        $java) = @_;
    die "Refusing to overwrite raw log $log\n" if -e $log;
    verify_execution_inputs($label, $identity, $benchmark_identity, $java);
    pipe my $reader, my $writer or die "Cannot create bounded output pipe: $!\n";
    my $pid = fork();
    die "Cannot fork $label: $!\n" unless defined $pid;
    if ($pid == 0) {
        close $reader;
        eval { POSIX::setpgid(0, 0) };
        open STDOUT, '>&', $writer or die "Cannot redirect output: $!\n";
        open STDERR, '>&', STDOUT or die "Cannot redirect STDERR: $!\n";
        close $writer;
        %ENV = (%closed_environment,
            PERLONJAVA_JAR => $identity->{jar}{path},
            PHASE36_SOURCE_COMMIT => $identity->{source}{commit},
            PHASE36_JAR_SHA256 => $identity->{jar}{sha256},
            PHASE36_PERFORMANCE_SIDE => $side_name);
        exec { $identity->{launcher}{path} }
            $identity->{launcher}{path}, $benchmark_identity->{path};
        die "Cannot exec $identity->{launcher}{path}: $!\n";
    }
    close $writer;
    my $has_process_group = establish_process_group($pid);
    my $status = collect_bounded($pid, $label, $log, $timeout,
        $has_process_group, $reader, 1024 * 1024);
    verify_execution_inputs($label, $identity, $benchmark_identity, $java);
    validate_status($status, $label, $log);
    my $text = read_raw($log);
    my @lines = grep { /^PHASE36_REGEX_PERFORMANCE\b/ } split /\r?\n/, $text;
    die "$label has missing or duplicate performance metrics; raw log: $log\n"
        unless @lines == 1;
    my @tokens = split /\s+/, $lines[0];
    die "$label metric prefix is malformed\n"
        unless shift(@tokens) eq 'PHASE36_REGEX_PERFORMANCE';
    my %metric;
    for my $token (@tokens) {
        die "$label performance metric token is malformed: $token\n"
            unless $token =~ /\A(elapsed_seconds|throughput|checksum|jar_sha256|source_commit)=([^=\s]+)\z/;
        die "$label repeats metric $1\n" if exists $metric{$1};
        $metric{$1} = $2;
    }
    for my $field (qw(elapsed_seconds throughput checksum jar_sha256 source_commit)) {
        die "$label performance metric $field is missing\n"
            unless defined $metric{$field} && length $metric{$field};
    }
    die "$label elapsed metric is malformed or outside the bounded range\n"
        unless bounded_positive_number($metric{elapsed_seconds}, 1_000_000, 0);
    die "$label throughput metric is malformed or outside the bounded range\n"
        unless bounded_positive_number($metric{throughput}, 1_000_000_000_000, 0);
    die "$label checksum metric is malformed\n"
        unless $metric{checksum} =~ /\A[[:alnum:]_.:+\/-]+\z/;
    die "$label used the wrong executable JAR\n"
        unless $metric{jar_sha256} eq $identity->{jar}{sha256};
    die "$label used the wrong source commit\n"
        unless $metric{source_commit} eq $identity->{source}{commit};
    return \%metric;
}

sub verify_execution_inputs {
    my ($label, $identity, $benchmark_identity, $java) = @_;
    for my $spec ([$identity->{jar}, 'JAR'], [$identity->{launcher}, 'launcher'],
            defined($benchmark_identity) ? [$benchmark_identity, 'benchmark'] : (),
            [$java, 'Java executable']) {
        my ($artifact, $kind) = @$spec;
        die "$label $kind disappeared during execution\n"
            unless -f $artifact->{path};
        die "$label $kind identity changed during execution\n"
            unless sha256_file($artifact->{path}) eq $artifact->{sha256};
    }
}

sub collect_bounded {
    my ($pid, $label, $log, $timeout, $has_process_group, $reader,
        $maximum_bytes) = @_;
    unless ($has_process_group) {
        kill 'TERM', $pid;
        sleep 0.2;
        kill 'KILL', $pid;
        waitpid($pid, 0);
        die "Cannot establish isolated process group for $label\n";
    }
    my ($written, $status, $leader_reaped, $eof) = (0, undef, 0, 0);
    my $output;
    my $ok = eval {
        sysopen $output, $log, O_WRONLY | O_CREAT | O_EXCL, 0600
            or die "Cannot create $log: $!\n";
        my $selector = IO::Select->new($reader);
        my $deadline = time() + $timeout;
        while (1) {
            for my $ready ($selector->can_read(0.02)) {
                my $count = sysread($ready, my $chunk, 64 * 1024);
                die "Cannot read $label output: $!\n" unless defined $count;
                if ($count == 0) {
                    $selector->remove($ready);
                    $eof = 1;
                } else {
                    die "$label exceeded its ${maximum_bytes}-byte log bound\n"
                        if $written + $count > $maximum_bytes;
                    print {$output} $chunk or die "Cannot write $log: $!\n";
                    $written += $count;
                }
            }
            my $waited = $leader_reaped ? 0 : waitpid($pid, WNOHANG);
            if ($waited == $pid) {
                $status = $?;
                $leader_reaped = 1;
            }
            last if $leader_reaped && $eof;
            die "waitpid failed for $label: $!\n" if $waited == -1;
            die "$label timed out after ${timeout}s; raw log: $log\n"
                if time() >= $deadline;
            sleep 0.02;
        }
        1;
    };
    my $error = $@;
    terminate_process_group($pid, $leader_reaped);
    close $reader if defined fileno($reader);
    if (defined($output) && defined(fileno($output)) && !close($output) && $ok) {
        $ok = 0;
        $error = "Cannot close $log: $!\n";
    }
    die $error unless $ok;
    return $status;
}

sub terminate_process_group {
    my ($pid, $leader_reaped) = @_;
    kill 'TERM', -$pid;
    sleep 0.2;
    kill 'KILL', -$pid;
    waitpid($pid, 0) unless $leader_reaped;
}

sub establish_process_group {
    my ($pid) = @_;
    eval { POSIX::setpgid($pid, $pid) };
    my $group = eval { getpgrp($pid) };
    return defined($group) && $group == $pid ? 1 : 0;
}

sub validate_status {
    my ($status, $label, $log) = @_;
    die "$label terminated by signal " . ($status & 127) . "; raw log: $log\n"
        if $status & 127;
    my $exit = $status >> 8;
    die "$label exited with status $exit; raw log: $log\n" if $exit != 0;
}

sub source_identity {
    my ($directory, $label) = @_;
    my $path = abs_path($directory)
        or die "Cannot resolve $label source directory $directory\n";
    die "$label source is not a directory: $directory\n" unless -d $path;
    my $commit = git_line($path, qw(rev-parse HEAD));
    die "$label source commit is not a full Git SHA\n"
        unless $commit =~ /\A[0-9a-f]{40}\z/;
    my $parents = git_output($path, qw(rev-list --parents -n 1 HEAD));
    $parents =~ s/\s+\z//;
    my @parent = split / /, $parents;
    die "$label source must have exactly one parent\n"
        unless @parent == 2 && $parent[0] eq $commit
            && $parent[1] =~ /\A[0-9a-f]{40}\z/;
    my $parent = $parent[1];
    my $state = git_output($path, qw(status --porcelain --untracked-files=all));
    die "$label source checkout is not clean\n" if length $state;
    return { path => $path, commit => $commit, parent_commit => $parent };
}

sub git_line {
    my ($directory, @args) = @_;
    my $output = git_output($directory, @args);
    $output =~ s/\s+\z//;
    die "Git command returned multiple lines in $directory\n" if $output =~ /\n/;
    return $output;
}

sub git_output {
    my ($directory, @args) = @_;
    die "authority-selected Git identity changed\n"
        unless sha256_file($option{git}) eq $git_sha256;
    local %ENV = (%closed_environment, GIT_CONFIG_NOSYSTEM => '1',
        GIT_CONFIG_GLOBAL => File::Spec->devnull());
    open my $fh, '-|', $option{git}, '--no-pager',
        '-c', 'core.fsmonitor=false', '-c', 'core.hooksPath=/dev/null',
        '-C', $directory, @args
        or die "Cannot execute git in $directory: $!\n";
    my $output = do { local $/; <$fh> };
    close $fh or die "Git command failed in $directory\n";
    die "authority-selected Git identity changed\n"
        unless sha256_file($option{git}) eq $git_sha256;
    return $output;
}

sub private_empty_directory {
    my ($directory) = @_;
    my $path = abs_path($directory)
        or die "Cannot resolve evidence directory $directory\n";
    die "Evidence directory is not a directory: $directory\n" unless -d $path;
    my $mode = (stat $path)[2] & 0777;
    die sprintf("Evidence directory must be private (mode is %04o)\n", $mode)
        if $mode & 0077;
    opendir my $dh, $path or die "Cannot inspect evidence directory $path: $!\n";
    my @entries = grep { $_ ne '.' && $_ ne '..' } readdir $dh;
    closedir $dh or die "Cannot close evidence directory $path: $!\n";
    die "Evidence directory is not empty: $path\n" if @entries;
    return $path;
}

sub absolute_output {
    my ($file) = @_;
    return File::Spec->rel2abs($file);
}

sub path_is_inside {
    my ($path, $directory) = @_;
    my $relative = File::Spec->abs2rel($path, $directory);
    return $relative ne File::Spec->updir
        && $relative !~ m{^\.\.(?:[\\/]|\z)};
}

sub file_identity {
    my ($file, $label, $executable) = @_;
    my $path = abs_path($file) or die "Cannot resolve $label $file\n";
    die "$label is missing or empty: $file\n" unless -f $path && -s $path;
    die "$label is not executable: $file\n" if $executable && !-x $path;
    return { path => $path, sha256 => sha256_file($path), size => 0 + (-s $path) };
}

sub immutable_signature {
    my ($sources, $sides, $benchmark_identity, $java_identity) = @_;
    my @parts;
    for my $name (qw(baseline candidate)) {
        push @parts, $name, git_line($sources->{$name}{path}, qw(rev-parse HEAD)),
            git_output($sources->{$name}{path}, qw(status --porcelain --untracked-files=all));
        for my $kind (qw(jar launcher)) {
            push @parts, $sides->{$name}{$kind}{path},
                sha256_file($sides->{$name}{$kind}{path});
        }
    }
    push @parts, $benchmark_identity->{path}, sha256_file($benchmark_identity->{path});
    push @parts, $java_identity->{path}, sha256_file($java_identity->{path});
    return sha256_hex(join "\0", @parts);
}

sub artifact {
    my ($file) = @_;
    return { path => abs_path($file), sha256 => sha256_file($file), size => 0 + (-s $file) };
}

sub median {
    my ($values) = @_;
    my @sorted = sort { $a <=> $b } @$values;
    my $middle = int(@sorted / 2);
    return @sorted % 2 ? $sorted[$middle]
        : ($sorted[$middle - 1] + $sorted[$middle]) / 2;
}

sub bounded_positive_number {
    my ($value, $maximum, $integer) = @_;
    return 0 unless defined($value) && !ref($value);
    my $text = "$value";
    return 0 if length($text) > 24;
    return 0 unless $integer
        ? $text =~ /\A\d{1,15}\z/
        : $text =~ /\A(?:\d{1,15}(?:\.\d{1,9})?|\.\d{1,9})\z/;
    my $numeric = 0 + $text;
    return 0 if $numeric != $numeric || $numeric <= 0;
    return $numeric <= $maximum;
}

sub sha256_file {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "Cannot read $file: $!\n";
    my $digest = Digest::SHA->new(256)->addfile($fh)->hexdigest;
    close $fh or die "Cannot close $file: $!\n";
    return $digest;
}

sub read_raw {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "Cannot read $file: $!\n";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $file: $!\n";
    return $contents;
}

sub write_json_exclusive {
    my ($file, $document) = @_;
    my $encoded = JSON::PP->new->utf8->canonical->pretty->encode($document);
    die "performance JSON exceeds its 8 MiB bound\n"
        if length($encoded) > 8 * 1024 * 1024;
    sysopen my $fh, $file, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot exclusively create $file: $!\n";
    binmode $fh, ':raw';
    print {$fh} $encoded
        or die "Cannot write $file: $!\n";
    close $fh or die "Cannot close $file: $!\n";
}

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: run_phase36_regex_performance.pl --baseline-source DIR --candidate-source DIR
       --baseline-jar FILE --candidate-jar FILE
       --baseline-launcher FILE --candidate-launcher FILE
       --java AUTHORITY_SELECTED_JAVA --git AUTHORITY_SELECTED_GIT
       --evidence-dir PRIVATE_EMPTY_DIR [OPTIONS]

Run one warmup per exact-parent side followed by at least five strictly
alternating baseline/candidate regex benchmark samples. Every child is bounded,
bound to its exact JAR and PERLONJAVA_JAVA_BIN, and required to emit exactly one:

  PHASE36_REGEX_PERFORMANCE elapsed_seconds=N throughput=N checksum=TOKEN \
      jar_sha256=SHA256 source_commit=GIT_SHA

Options: --benchmark FILE (defaults to the checked-in canonical benchmark),
--samples N (default 5), --timeout N (default 300), --output FILE.
USAGE
    exit $status;
}
