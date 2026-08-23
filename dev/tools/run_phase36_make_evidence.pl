#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use Fcntl qw(F_GETFL F_SETFL O_CREAT O_EXCL O_NONBLOCK O_WRONLY);
use File::Basename qw(basename dirname);
use File::Spec;
use Getopt::Long qw(GetOptions);
use IO::Select;
use JSON::PP;
use POSIX qw(WNOHANG strftime);
use Time::HiRes qw(sleep time);

my $SCHEMA = 'perlonjava.phase36.make-evidence/v1';
my $CLASSIFIER_VERSION = 'phase36-make-warning-failure/v1';
my $MAX_TIMEOUT = 7_200;
my $MAX_LOG = 32 * 1024 * 1024;
my $MAX_CAPTURE = 1024 * 1024;
my $MAX_INPUT = 64 * 1024 * 1024;
my $MAX_JAR = 512 * 1024 * 1024;
my $MAX_JSON = 8 * 1024 * 1024;
my %option = (mode => 'acceptance', timeout => 1800);
my $capture_sequence = 0;

reject_duplicate_options(\@ARGV);
GetOptions(
    'source-root=s' => \$option{source_root},
    'expected-source-commit=s' => \$option{expected_source_commit},
    'expected-runner-commit=s' => \$option{expected_runner_commit},
    'expected-jar=s' => \$option{expected_jar},
    'expected-jar-sha256=s' => \$option{expected_jar_sha256},
    'output=s' => \$option{output},
    'perl=s' => \$option{perl},
    'git=s' => \$option{git},
    'make=s' => \$option{make},
    'shell=s' => \$option{shell},
    'java=s' => \$option{java},
    'timeout=s' => \$option{timeout},
    'mode=s' => \$option{mode},
    'help' => \$option{help},
) or usage(2);
usage(0) if $option{help};
usage(2) if @ARGV;

for my $name (qw(source_root expected_source_commit expected_runner_commit
        expected_jar expected_jar_sha256 output perl git make shell java)) {
    (my $display = $name) =~ tr/_/-/;
    die "--$display is required\n"
        unless defined $option{$name} && length $option{$name};
}
die "--mode must be acceptance or report\n"
    unless $option{mode} eq 'acceptance' || $option{mode} eq 'report';
die "Report output must end in .report.json\n"
    if $option{mode} eq 'report' && $option{output} !~ /\.report\.json\z/;
die "Acceptance output must not use the report suffix\n"
    if $option{mode} eq 'acceptance' && $option{output} =~ /\.report\.json\z/;
for my $name (qw(expected_source_commit expected_runner_commit)) {
    die "--$name must be a full lowercase Git SHA-1\n"
        unless $option{$name} =~ /\A[0-9a-f]{40}\z/;
}
die "Source and runner commits must be identical\n"
    unless $option{expected_source_commit} eq $option{expected_runner_commit};
die "--expected-jar-sha256 must be a lowercase SHA-256\n"
    unless $option{expected_jar_sha256} =~ /\A[0-9a-f]{64}\z/;
die "--timeout must be a canonical integer from 1 through $MAX_TIMEOUT\n"
    unless $option{timeout} =~ /\A(?:[1-9]|[1-9][0-9]{1,3})\z/
        && $option{timeout} <= $MAX_TIMEOUT;
$option{timeout} = 0 + $option{timeout};

my ($output, $output_parent) = confined_new_path($option{output}, 'output');
my @parent_stat = stat $output_parent;
die "Cannot identify output parent\n" unless @parent_stat;
my ($parent_device, $parent_inode) = @parent_stat[0, 1];
die "Refusing output collision at $output\n" if -e $output || -l $output;

my $ok = eval { produce(); 1 };
my $error = $@;
if (!$ok && $option{mode} eq 'report') {
    my $report = {
        schema => 'perlonjava.phase36.make-evidence-report/v1',
        schema_version => 1,
        kind => 'make-report',
        producer => 'run_phase36_make_evidence.pl',
        mode => 'report', status => 'fail', verified => JSON::PP::false,
        authoritative => JSON::PP::false,
        error => bounded_error($error),
    };
    write_exclusive($output, canonical_pretty($report), $MAX_JSON, 'report');
    print "$output\n";
    exit 1;
}
die $error unless $ok;
print "$output\n";

sub produce {
    my $source = canonical_directory($option{source_root}, 'source root');
    die "Output must be outside the source root\n"
        if path_is_inside($output_parent, $source);
    my $jar = canonical_future_file($option{expected_jar}, $source, 'expected JAR');
    my %tool = map { $_ => executable_identity($option{$_}, "$_ executable") }
        qw(perl git make shell java);
    die "The running Perl does not match --perl\n"
        unless abs_path($^X) eq $tool{perl}{path}
            && sha256_file(abs_path($^X), $MAX_INPUT, 'running Perl') eq $tool{perl}{sha256};

    my $producer = strict_source_file(File::Spec->rel2abs($0), $source,
        'producer', $tool{git}{path});
    die "Producer basename is not authoritative\n"
        unless basename($producer->{path}) eq 'run_phase36_make_evidence.pl';
    my %input;
    for my $entry (
        [gradlew => 'gradlew'],
        [gradle_wrapper_jar => 'gradle/wrapper/gradle-wrapper.jar'],
        [gradle_wrapper_properties => 'gradle/wrapper/gradle-wrapper.properties'],
        [makefile => 'Makefile'], [build_gradle => 'build.gradle'],
        [settings_gradle => 'settings.gradle'],
    ) {
        $input{$entry->[0]} = strict_source_file(
            File::Spec->catfile($source, split m{/}, $entry->[1]),
            $source, $entry->[0], $tool{git}{path});
    }
    die "gradlew is not executable\n" unless -x $input{gradlew}{path};

    my $source_before = source_identity($source, $tool{git}{path});
    die "Source HEAD differs from the trusted source commit\n"
        unless $source_before->{head} eq $option{expected_source_commit};
    die "Source is dirty before make evidence capture\n"
        unless $source_before->{tracked_clean};
    my $jar_before = -e $jar || -l $jar ? file_snapshot($jar, $MAX_JAR, 'pre-build JAR') : undef;

    my %version = (
        git => capture_bounded([$tool{git}{path}, '--version'], 'git version'),
        make => capture_bounded([$tool{make}{path}, '--version'], 'make version'),
        perl => capture_bounded([$tool{perl}{path}, '-v'], 'Perl version'),
        shell => capture_bounded([$tool{shell}{path}, '--version'], 'shell version'),
        java => capture_bounded([$tool{java}{path}, '-version'], 'Java version'),
        gradle_wrapper => capture_bounded([$input{gradlew}{path}, '--version'], 'Gradle wrapper version'),
    );
    verify_identities(\%tool, \%input, $producer);
    my $versions_bytes = canonical_pretty(\%version);

    my @path = unique(map { dirname($tool{$_}{path}) } qw(make java shell git perl));
    my $environment = {
        PATH => join(':', @path), SHELL => $tool{shell}{path},
        JAVA_HOME => dirname(dirname($tool{java}{path})),
        HOME => $output_parent, GRADLE_USER_HOME => $output_parent,
        TMPDIR => $output_parent, LC_ALL => 'C', LANG => 'C', TZ => 'UTC',
        GIT_CONFIG_NOSYSTEM => '1', GIT_CONFIG_GLOBAL => '/dev/null',
        MAKEFLAGS => '', MFLAGS => '', GNUMAKEFLAGS => '',
        SOURCE_DATE_EPOCH => git_line($source, $tool{git}{path},
            'show', '-s', '--format=%ct', $source_before->{head}),
    };
    die "Malformed SOURCE_DATE_EPOCH\n"
        unless $environment->{SOURCE_DATE_EPOCH} =~ /\A(?:0|[1-9][0-9]{0,11})\z/;

    my $stamp = join('-', $$, int(time() * 1_000_000), int(rand(1_000_000)));
    my %stage = map { $_ => "$output.stage-$stamp.$_" }
        qw(log before after versions jar_version json seal);
    my %final = (
        log => "$output.make.log", before => "$output.source-before.json",
        after => "$output.source-after.json", versions => "$output.tool-versions.json",
        jar_version => "$output.jar-version.log", seal => "$output.seal",
    );
    for my $path (values %stage, values %final) {
        die "Publication path already exists: $path\n" if -e $path || -l $path;
    }

    my @argv = ($tool{make}{path});
    my ($published, $success, $failure) = ([], 0, undef);
    eval {
        write_exclusive($stage{before}, canonical_pretty($source_before),
            $MAX_JSON, 'source-before stage');
        write_exclusive($stage{versions}, $versions_bytes, $MAX_JSON, 'tool-version stage');
        my $run = run_bounded(\@argv, $source, $environment, $stage{log},
            $option{timeout}, $MAX_LOG);
        validate_completion($run);
        my $log = read_once_stable($stage{log}, $MAX_LOG, 'make log');
        my $log_pin = stat_identity($stage{log}, 'make log');
        die "Captured make log differs from process stream\n"
            unless sha256_hex($log) eq $run->{log_sha256}
                && length($log) == $run->{log_size};
        my ($warnings, $failures) = classify_log($log);
        die "Make log contains warning output\n" if @$warnings;
        die "Make log contains failure output\n" if @$failures;
        my @success_markers = $log =~ /^BUILD SUCCESSFUL(?:\s+in\s+.+)?\s*$/mg;
        die "Make log must contain exactly one BUILD SUCCESSFUL marker\n"
            unless @success_markers == 1;
        die "Make log contains BUILD FAILED\n" if $log =~ /^BUILD FAILED\b/m;

        my $source_after = source_identity($source, $tool{git}{path});
        die "Source HEAD changed during make evidence capture\n"
            unless $source_after->{head} eq $source_before->{head};
        die "Source became dirty during make evidence capture\n"
            unless $source_after->{tracked_clean};

        die "Expected JAR was not freshly produced\n" unless -f $jar && !-l $jar;
        my $jar_after = file_snapshot($jar, $MAX_JAR, 'produced JAR');
        if ($jar_before) {
            die "Produced JAR is stale\n"
                if same_file_snapshot($jar_before, $jar_after);
        }
        die "Produced JAR SHA-256 differs from trusted expectation\n"
            unless $jar_after->{sha256} eq $option{expected_jar_sha256};
        my $jar_version = capture_bounded(
            [$tool{java}{path}, '-jar', $jar, '-v'], 'JAR version');
        write_exclusive($stage{jar_version}, $jar_version,
            $MAX_CAPTURE, 'JAR-version stage');
        my $reported_commit = extract_commit($jar_version,
            $option{expected_source_commit});

        write_exclusive($stage{after}, canonical_pretty($source_after),
            $MAX_JSON, 'source-after stage');
        verify_identities(\%tool, \%input, $producer);
        verify_snapshot($jar_after, $MAX_JAR, 'produced JAR');

        my %artifact = (
            make_log => { path => $final{log}, sha256 => sha256_hex($log),
                size => length($log) },
            source_before => descriptor($final{before}, $stage{before}),
            source_after => descriptor($final{after}, $stage{after}),
            tool_versions => descriptor($final{versions}, $stage{versions}),
            jar => public_file($jar_after),
            jar_version => descriptor($final{jar_version}, $stage{jar_version}),
        );
        my $classifier_sha = sha256_hex(join("\n", $CLASSIFIER_VERSION,
            warning_patterns(), failure_patterns()));
        my $started_utc = utc($run->{started_epoch});
        my $finished_utc = utc($run->{finished_epoch});
        my $payload = {
            schema => $SCHEMA, schema_version => 1, kind => 'make',
            producer => 'run_phase36_make_evidence.pl', mode => 'acceptance',
            status => 'pass', verified => JSON::PP::true,
            authoritative => JSON::PP::true,
            identity => {
                source_commit => $source_before->{head},
                runner_commit => $option{expected_runner_commit},
                jar_sha256 => $jar_after->{sha256},
                jar_reported_commit => $reported_commit,
            },
            source => { root => $source, before => $source_before,
                after => $source_after },
            command => { cwd => $source, argv => \@argv,
                environment => $environment, started_utc => $started_utc,
                finished_utc => $finished_utc,
                duration_milliseconds => int($run->{duration_seconds} * 1000) },
            tools => {
                map { $_ => public_tool($tool{$_}, $version{$_}) } keys %tool,
                producer => public_file($producer),
            },
            inputs => {
                map { $_ => public_file($input{$_}) } keys %input,
            },
            completion => { exit_code => 0, signal => 0,
                timeout => JSON::PP::false, incomplete => JSON::PP::false,
                truncated => JSON::PP::false, review_stop => JSON::PP::false },
            warning_scan => { classifier => $CLASSIFIER_VERSION,
                classifier_sha256 => $classifier_sha,
                complete_log_sha256 => $artifact{make_log}{sha256},
                count => 0, matches => [] },
            failure_scan => { classifier => $CLASSIFIER_VERSION,
                classifier_sha256 => $classifier_sha,
                complete_log_sha256 => $artifact{make_log}{sha256},
                count => 0, matches => [] },
            artifacts => \%artifact,
        };
        my $payload_sha = sha256_hex(canonical($payload));
        my $document = { %$payload, seal => {
            algorithm => 'SHA-256', payload_sha256 => $payload_sha } };
        my $json = canonical_pretty($document);
        my $seal = "SHA-256 $payload_sha " . sha256_hex($json) . "\n";
        write_exclusive($stage{json}, $json, $MAX_JSON, 'JSON stage');
        write_exclusive($stage{seal}, $seal, 512, 'seal stage');

        verify_publication_boundary($source, $source_after, \%tool, \%input,
            $producer, $jar_after);
        verify_stat_identity($stage{log}, $log_pin, 'make log');
        verify_output_parent();
        for my $name (qw(log before after versions jar_version)) {
            publish_link($stage{$name}, $final{$name}, $published);
        }
        my $published_log_pin = stat_identity($final{log}, 'published make log');
        publish_link($stage{json}, $output, $published);
        publish_link($stage{seal}, $final{seal}, $published);
        verify_publication_boundary($source, $source_after, \%tool, \%input,
            $producer, $jar_after);
        verify_stat_identity($final{log}, $published_log_pin, 'published make log');
        $success = 1;
        1;
    } or $failure = $@;
    for my $path (values %stage) { unlink $path if -e $path || -l $path }
    unless ($success) {
        for my $path (reverse @$published) { unlink $path if -e $path || -l $path }
        die $failure;
    }
}

sub source_identity {
    my ($root, $git) = @_;
    my $head = git_line($root, $git, 'rev-parse', 'HEAD');
    die "Malformed source HEAD bytes: " . unpack('H*', $head) . "\n"
        unless $head =~ /\A[0-9a-f]{40}\z/;
    my $status = git_capture($root, $git, 'status', '--porcelain=v1',
        '--untracked-files=no');
    my $diff = git_capture($root, $git, 'diff', '--binary', 'HEAD', '--');
    return { head => $head, tracked_clean => length($status) ? JSON::PP::false : JSON::PP::true,
        status_sha256 => sha256_hex($status), diff_sha256 => sha256_hex($diff) };
}

sub strict_source_file {
    my ($path, $root, $label, $git) = @_;
    my $absolute = strict_regular_file($path, $MAX_INPUT, $label);
    die "$label escapes source root\n" unless path_is_inside($absolute, $root);
    git_capture($root, $git, 'ls-files', '--error-unmatch', '--',
        File::Spec->abs2rel($absolute, $root));
    return file_snapshot($absolute, $MAX_INPUT, $label);
}

sub executable_identity {
    my ($path, $label) = @_;
    my $absolute = strict_regular_file($path, $MAX_INPUT, $label);
    die "$label is not executable\n" unless -x $absolute;
    return file_snapshot($absolute, $MAX_INPUT, $label);
}

sub file_snapshot {
    my ($path, $limit, $label) = @_;
    my $absolute = strict_regular_file($path, $limit, $label);
    my @stat = stat $absolute;
    return { path => $absolute, sha256 => sha256_file($absolute, $limit, $label),
        size => 0 + $stat[7], device => 0 + $stat[0], inode => 0 + $stat[1],
        mtime => 0 + $stat[9], ctime => 0 + $stat[10] };
}

sub same_file_snapshot {
    my ($a, $b) = @_;
    return canonical($a) eq canonical($b);
}

sub verify_snapshot {
    my ($old, $limit, $label) = @_;
    my $new = file_snapshot($old->{path}, $limit, $label);
    die "$label mutated during evidence capture\n"
        unless canonical($old) eq canonical($new);
}

sub verify_identities {
    my ($tools, $inputs, $producer) = @_;
    verify_snapshot($producer, $MAX_INPUT, 'producer');
    verify_snapshot($tools->{$_}, $MAX_INPUT, "$_ executable") for keys %$tools;
    verify_snapshot($inputs->{$_}, $MAX_INPUT, "$_ input") for keys %$inputs;
}

sub verify_publication_boundary {
    my ($source, $source_after, $tools, $inputs, $producer, $jar) = @_;
    die "Source changed at publication boundary\n"
        unless canonical(source_identity($source, $tools->{git}{path}))
            eq canonical($source_after);
    verify_identities($tools, $inputs, $producer);
    verify_snapshot($jar, $MAX_JAR, 'produced JAR');
}

sub run_bounded {
    my ($argv, $cwd, $environment, $log, $timeout, $limit) = @_;
    verify_output_parent();
    sysopen my $log_fh, $log, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot exclusively create make log: $!\n";
    pipe my $reader, my $writer or die "Cannot create make output pipe: $!\n";
    my $flags = fcntl($reader, F_GETFL, 0);
    die "Cannot inspect make pipe: $!\n" unless defined $flags;
    fcntl($reader, F_SETFL, $flags | O_NONBLOCK)
        or die "Cannot make output pipe nonblocking: $!\n";
    my ($interrupted, $timed_out, $truncated);
    local $SIG{$_} = sub { $interrupted = $_ } for qw(INT TERM HUP QUIT);
    my $started = time();
    my $pid = fork();
    die "Cannot fork make: $!\n" unless defined $pid;
    if ($pid == 0) {
        $SIG{$_} = 'DEFAULT' for qw(INT TERM HUP QUIT);
        close $reader; close $log_fh;
        POSIX::setpgid(0, 0) == 0 or die "Cannot create make process group: $!\n";
        chdir $cwd or die "Cannot chdir to source root: $!\n";
        open STDOUT, '>&', $writer or die "Cannot redirect make stdout: $!\n";
        open STDERR, '>&', STDOUT or die "Cannot redirect make stderr: $!\n";
        close $writer;
        %ENV = %$environment;
        exec { $argv->[0] } @$argv;
        die "Cannot execute make: $!\n";
    }
    close $writer;
    POSIX::setpgid($pid, $pid);
    my $select = IO::Select->new($reader);
    my ($status, $eof, $size) = (undef, 0, 0);
    my $digest = Digest::SHA->new(256);
    while (!defined($status) || !$eof) {
        for my $fh ($select->can_read(0.02)) {
            while (1) {
                my $n = sysread $fh, my $chunk, 65536;
                if (defined $n && $n) {
                    if ($size + $n > $limit) { $truncated = 1; last }
                    write_all($log_fh, $chunk, 'make log');
                    $digest->add($chunk); $size += $n; next;
                }
                if (defined $n) { $eof = 1; $select->remove($fh) }
                elsif (!$!{EAGAIN} && !$!{EWOULDBLOCK}) { $truncated = 1 }
                last;
            }
        }
        if (!defined $status) {
            my $waited = waitpid($pid, WNOHANG);
            $status = $? if $waited == $pid;
            die "waitpid failed for make: $!\n" if $waited == -1;
        }
        $timed_out = 1 if !defined($status) && time() - $started >= $timeout;
        if (($timed_out || $truncated || $interrupted) && !defined $status) {
            terminate_group($pid); waitpid($pid, 0); $status = $?;
        }
        if (defined $status && !$eof && ($timed_out || $truncated || $interrupted)) {
            terminate_group($pid); last;
        }
    }
    terminate_group($pid);
    close $reader; close $log_fh or die "Cannot close make log: $!\n";
    my $finished = time();
    return { exit_code => $status >> 8, signal => $status & 127,
        timeout => $timed_out ? 1 : 0, truncated => $truncated ? 1 : 0,
        interrupted => $interrupted, started_epoch => $started,
        finished_epoch => $finished, duration_seconds => $finished - $started,
        log_size => $size, log_sha256 => $digest->hexdigest };
}

sub validate_completion {
    my ($run) = @_;
    die "Make timed out\n" if $run->{timeout};
    die "Make log exceeded bounded size\n" if $run->{truncated};
    die "Make was interrupted by $run->{interrupted}\n" if $run->{interrupted};
    die "Make terminated by signal $run->{signal}\n" if $run->{signal};
    die "Make exited with status $run->{exit_code}\n" if $run->{exit_code};
}

sub terminate_group {
    my ($pid) = @_;
    return unless kill 0, -$pid;
    kill 'TERM', -$pid;
    my $deadline = time() + 0.25;
    sleep 0.01 while time() < $deadline && kill(0, -$pid);
    kill 'KILL', -$pid if kill 0, -$pid;
    $deadline = time() + 1;
    sleep 0.01 while time() < $deadline && kill(0, -$pid);
    die "Cannot terminate make process group $pid\n" if kill 0, -$pid;
}

sub classify_log {
    my ($log) = @_;
    my (@warning, @failure);
    for my $line (split /\n/, $log, -1) {
        next if $line =~ /^\s*(?:ok|not ok)\b/ || $line =~ /^\s*#/;
        push @warning, $line if warning_line($line);
        push @failure, $line if failure_line($line);
    }
    return (\@warning, \@failure);
}

sub warning_line {
    my ($line) = @_;
    return $line =~ /(?:\bwarning:|\bWARNING:)/
        || $line =~ /\bUse of uninitialized value\b/
        || $line =~ /\b(?:Argument .+ |.+ )isn't numeric\b/
        || $line =~ /\bPossible unintended interpolation\b/
        || $line =~ /\bWide character in\b/
        || $line =~ /\bSubroutine\s+\S+\s+redefined\b/
        || $line =~ /\bat\s+\S.*\s+line\s+[1-9][0-9]*\.?\s*\z/;
}

sub failure_line {
    my ($line) = @_;
    return $line =~ /^BUILD FAILED\b/
        || $line =~ /^FAILURE:/
        || $line =~ /\b(?:FAILED|FATAL|ERROR:)\b/
        || $line =~ /Gradle build daemon disappeared unexpectedly/i;
}

sub warning_patterns { return join("\n", qw(warning: WARNING: uninitialized nonnumeric interpolation wide-character subroutine-redefined non-TAP-at-line)) }
sub failure_patterns { return join("\n", qw(BUILD-FAILED FAILURE FAILED FATAL ERROR daemon-disappeared)) }

sub extract_commit {
    my ($text, $expected) = @_;
    my @token = $text =~ /(?<![0-9a-f])([0-9a-f]{7,40})(?![0-9a-f])/g;
    my %unique = map { $_ => 1 } @token;
    my @matching = grep { index($expected, $_) == 0 } keys %unique;
    die "JAR version output has no unique source commit binding\n"
        unless @matching == 1;
    die "JAR version output contains conflicting commit identities\n"
        if grep { index($expected, $_) != 0 } keys %unique;
    return $expected;
}

sub capture_bounded {
    my ($argv, $label) = @_;
    my $path = "$output.capture-$$-" . ++$capture_sequence;
    my $run;
    my $ok = eval {
        $run = run_capture($argv, $path, 30, $MAX_CAPTURE, $label); 1
    };
    my $error = $@;
    unlink $path if -e $path || -l $path;
    die $error unless $ok;
    return $run;
}

sub run_capture {
    my ($argv, $path, $timeout, $limit, $label) = @_;
    my $pid = fork(); die "Cannot fork $label: $!\n" unless defined $pid;
    if ($pid == 0) {
        open STDOUT, '>', $path or die "Cannot create $label capture: $!\n";
        open STDERR, '>&', STDOUT or die "Cannot redirect $label: $!\n";
        %ENV = (LC_ALL => 'C', LANG => 'C', PATH => dirname($argv->[0]),
            HOME => $output_parent, TMPDIR => $output_parent);
        exec { $argv->[0] } @$argv; die "Cannot execute $label: $!\n";
    }
    my $deadline = time() + $timeout;
    my $status;
    while (1) {
        my $waited = waitpid($pid, WNOHANG);
        if ($waited == $pid) { $status = $?; last }
        die "waitpid failed for $label: $!\n" if $waited == -1;
        if (time() >= $deadline) { kill 'KILL', $pid; waitpid($pid, 0); die "$label timed out\n" }
        sleep 0.01;
    }
    die "$label terminated by signal " . ($status & 127) . "\n" if $status & 127;
    die "$label exited with status " . ($status >> 8) . "\n" if $status >> 8;
    return read_once_stable($path, $limit, $label);
}

sub git_capture {
    my ($root, $git, @args) = @_;
    return capture_bounded([$git, '-C', $root, @args], 'git');
}
sub git_line { my $x = git_capture(@_); $x =~ s/\s+\z//; return $x }

sub strict_regular_file {
    my ($path, $limit, $label) = @_;
    die "$label path must be absolute and canonical\n"
        unless File::Spec->file_name_is_absolute($path)
            && File::Spec->canonpath($path) eq $path;
    reject_symlink_components($path, $label);
    die "$label is missing or not a regular file\n" unless -f $path && !-l $path;
    my @stat = stat $path;
    die "$label exceeds bounded size of $limit bytes\n" if $stat[7] > $limit;
    my $resolved = abs_path($path);
    die "$label is not canonical\n" unless defined $resolved && $resolved eq $path;
    return $resolved;
}

sub canonical_future_file {
    my ($path, $root, $label) = @_;
    die "$label path must be absolute and canonical\n"
        unless File::Spec->file_name_is_absolute($path)
            && File::Spec->canonpath($path) eq $path;
    my $parent = abs_path(dirname($path));
    die "$label parent is missing\n" unless defined $parent && -d $parent;
    die "$label escapes source root\n" unless path_is_inside($path, $root);
    reject_symlink_components(dirname($path), $label);
    return File::Spec->catfile($parent, basename($path));
}

sub canonical_directory {
    my ($path, $label) = @_;
    die "$label must be absolute and canonical\n"
        unless File::Spec->file_name_is_absolute($path)
            && File::Spec->canonpath($path) eq $path;
    reject_symlink_components($path, $label);
    my $resolved = abs_path($path);
    die "$label is missing or not canonical\n"
        unless defined $resolved && -d $resolved && $resolved eq $path;
    return $resolved;
}

sub reject_symlink_components {
    my ($path, $label) = @_;
    my @part = File::Spec->splitdir(File::Spec->canonpath($path));
    my $current = File::Spec->rootdir;
    for my $part (@part) {
        next if $part eq '' || $part eq File::Spec->rootdir;
        $current = File::Spec->catfile($current, $part);
        die "$label path contains a symlink component\n" if -l $current;
    }
}

sub confined_new_path {
    my ($path, $label) = @_;
    die "--$label must be absolute and canonical\n"
        unless File::Spec->file_name_is_absolute($path)
            && File::Spec->canonpath($path) eq $path;
    my $name = basename($path);
    die "Unsafe $label filename\n" if $name =~ /[\\\/\r\n\0]/ || $name eq '.' || $name eq '..';
    my $parent = canonical_directory(dirname($path), "$label parent");
    my $resolved = File::Spec->catfile($parent, $name);
    die "$label escaped its parent\n" unless $resolved eq $path;
    return ($resolved, $parent);
}

sub reject_duplicate_options {
    my ($argv) = @_;
    my %seen;
    for my $arg (@$argv) {
        next unless $arg =~ /\A--([a-z][a-z0-9-]*)(?:=|\z)/;
        die "Duplicate option --$1\n" if $seen{$1}++;
        die "Caller-supplied log or summary is forbidden\n"
            if $1 =~ /\A(?:log|summary|evidence|environment|path)\z/;
    }
}

sub read_once_stable {
    my ($path, $limit, $label) = @_;
    my @before = lstat $path;
    die "$label is missing or symlinked\n" unless @before && -f _ && !-l _;
    die "$label exceeds bounded size of $limit bytes\n" if $before[7] > $limit;
    open my $fh, '<:raw', $path or die "Cannot read $label: $!\n";
    my $bytes = '';
    while (1) {
        my $n = read $fh, my $chunk, 65536;
        die "Cannot read $label: $!\n" unless defined $n;
        last unless $n; $bytes .= $chunk;
        die "$label exceeds bounded size of $limit bytes\n" if length($bytes) > $limit;
    }
    close $fh or die "Cannot close $label: $!\n";
    my @after = lstat $path;
    die "$label mutated while read\n"
        unless @after && join(':', @before[0,1,7,9,10]) eq join(':', @after[0,1,7,9,10]);
    return $bytes;
}

sub sha256_file {
    my ($path, $limit, $label) = @_;
    return sha256_hex(read_once_stable($path, $limit, $label));
}

sub descriptor {
    my ($final, $stage) = @_;
    my @stat = stat $stage;
    return { path => $final, sha256 => sha256_file($stage, $MAX_LOG, 'artifact'),
        size => 0 + $stat[7] };
}

sub stat_identity {
    my ($path, $label) = @_;
    my @stat = lstat $path;
    die "$label is missing or symlinked\n" unless @stat && -f _ && !-l _;
    return [@stat[0, 1, 7, 9, 10]];
}

sub verify_stat_identity {
    my ($path, $expected, $label) = @_;
    my $actual = stat_identity($path, $label);
    die "$label mutated after authoritative read\n"
        unless join(':', @$actual) eq join(':', @$expected);
}

sub public_file {
    my ($identity) = @_;
    return { path => $identity->{path}, sha256 => $identity->{sha256},
        size => 0 + $identity->{size} };
}

sub public_tool {
    my ($identity, $version) = @_;
    return { %{public_file($identity)}, version_sha256 => sha256_hex($version) };
}

sub publish_link {
    my ($stage, $final, $published) = @_;
    verify_output_parent();
    die "Publication collision at $final\n" if -e $final || -l $final;
    link $stage, $final or die "Cannot exclusively publish $final: $!\n";
    push @$published, $final;
}

sub write_exclusive {
    my ($path, $bytes, $limit, $label) = @_;
    die "$label exceeds bounded size\n" if length($bytes) > $limit;
    verify_output_parent();
    sysopen my $fh, $path, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot exclusively create $label: $!\n";
    write_all($fh, $bytes, $label);
    close $fh or die "Cannot close $label: $!\n";
}

sub write_all {
    my ($fh, $bytes, $label) = @_;
    my $offset = 0;
    while ($offset < length $bytes) {
        my $n = syswrite $fh, $bytes, length($bytes) - $offset, $offset;
        die "Cannot write $label: $!\n" unless defined $n && $n;
        $offset += $n;
    }
}

sub verify_output_parent {
    my $resolved = abs_path($output_parent);
    my @now = stat $output_parent;
    die "Output parent changed during capture\n"
        unless defined $resolved && $resolved eq $output_parent && @now
            && $now[0] == $parent_device && $now[1] == $parent_inode;
}

sub path_is_inside {
    my ($path, $root) = @_;
    my $relative = File::Spec->abs2rel($path, $root);
    return $relative ne File::Spec->updir && $relative !~ /^\.\.(?:[\\\/]\z|[\\\/])/;
}

sub utc { return strftime('%Y-%m-%dT%H:%M:%SZ', gmtime($_[0])) }
sub unique { my %seen; return grep { !$seen{$_}++ } @_ }
sub canonical { JSON::PP->new->utf8->canonical->encode($_[0]) }
sub canonical_pretty { JSON::PP->new->utf8->canonical->pretty->encode($_[0]) }
sub bounded_error { my $x = $_[0] // 'unknown failure'; $x =~ s/[\r\n]+/ /g; return substr($x, 0, 4096) }

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: perl dev/tools/run_phase36_make_evidence.pl OPTIONS
  --source-root ABSOLUTE_PATH
  --expected-source-commit FULL_SHA1
  --expected-runner-commit FULL_SHA1
  --expected-jar ABSOLUTE_PATH
  --expected-jar-sha256 SHA256
  --output ABSOLUTE_NEW_JSON
  --perl ABSOLUTE_PATH --git ABSOLUTE_PATH --make ABSOLUTE_PATH
  --shell ABSOLUTE_PATH --java ABSOLUTE_PATH
  --timeout SECONDS             1..7200 (default: 1800)
  --mode acceptance|report     report output must end .report.json

The producer always executes exactly the absolute make executable with no
arguments in the exact source root. Caller log, summary, environment, and PATH
inputs are forbidden.
USAGE
    exit $status;
}
