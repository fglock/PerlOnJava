#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path getcwd);
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname);
use File::Spec;
use Getopt::Long qw(GetOptions);
use JSON::PP;

my %option = (
    perl => $^X,
    ledger_tool => 'dev/tools/generate_regex_test_ledger.pl',
    ledger_scope => 'complete',
    runner_tool => 'dev/tools/perl_test_runner.pl',
    comparator_tool => 'dev/tools/compare_test_results.pl',
    packaging_tool => 'dev/tools/verify-joni-packaging.pl',
    jperl => './jperl',
    timeout => 300,
    version_timeout => 30,
    jobs => 5,
);
my $help;
GetOptions(
    'baseline=s' => \$option{baseline},
    'artifact-dir=s' => \$option{artifact_dir},
    'jar=s' => \$option{jar},
    'sbom=s' => \$option{sbom},
    'perl=s' => \$option{perl},
    'source-dir=s' => \$option{source_dir},
    'perl5-dir=s' => \$option{perl5_dir},
    'jperl=s' => \$option{jperl},
    'timeout=i' => \$option{timeout},
    'version-timeout=i' => \$option{version_timeout},
    'jobs=i' => \$option{jobs},
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
            ledger_tool => 'dev/tools/generate_regex_test_ledger.pl',
            runner_tool => 'dev/tools/perl_test_runner.pl',
            comparator_tool => 'dev/tools/compare_test_results.pl',
            packaging_tool => 'dev/tools/verify-joni-packaging.pl',
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
die "--timeout must be positive\n" unless $option{timeout} > 0;
die "--version-timeout must be positive\n" unless $option{version_timeout} > 0;
die "--jobs must be positive\n" unless $option{jobs} > 0;
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

my $start_sha = git_sha($root);
my $start_state = tracked_state($root);
my $perl5_sha = git_sha($option{perl5_dir});
my %input_sha = map { $_ => sha256_file($option{$_}) } qw(baseline jar sbom jperl);
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
    timeout => $option{version_timeout}, environment => { PERLONJAVA_JAR => $option{jar} },
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
    '--jperl', $option{jperl}, '--timeout', $option{timeout}, '--jobs', $option{jobs});
run_logged(
    name => 'jvm-runner',
    command => [@runner_common, '--output', $path{'jvm-results.json'}, @files],
    log => $path{'jvm-runner.log'},
    environment => { JPERL_INTERPRETER => undef, PERLONJAVA_JAR => $option{jar} },
    commands => \@commands, statuses => \%statuses,
);
run_logged(
    name => 'interpreter-runner',
    command => [@runner_common, '--output', $path{'interpreter-results.json'}, @files],
    log => $path{'interpreter-runner.log'},
    environment => { JPERL_INTERPRETER => 1, PERLONJAVA_JAR => $option{jar} },
    commands => \@commands, statuses => \%statuses,
);

for my $leg (
    ['jvm', $path{'jvm-results.json'}, $path{'jvm-comparison.json'}],
    ['interpreter', $path{'interpreter-results.json'}, $path{'interpreter-comparison.json'}],
) {
    run_logged(
        name => "$leg->[0]-comparison",
        command => [$option{perl}, $option{comparator_tool},
            '--fail-on-regression', '--fail-on-new-invalid',
            '--expected-files', $expected_files,
            '--file-list', $path{'regex-files.txt'}, '--output', $leg->[2],
            $option{baseline}, $leg->[1]],
        log => $path{"$leg->[0]-comparison.log"},
        commands => \@commands, statuses => \%statuses,
    );
    verify_comparison($leg->[2], $expected_files, 1);

    my $strict_output = $path{"$leg->[0]-strict-regex-comparison.json"};
    run_logged(
        name => "$leg->[0]-strict-regex-comparison",
        command => [$option{perl}, $option{comparator_tool},
            '--fail-on-regression', '--fail-on-invalid',
            '--expected-files', $strict_regex_expected_files,
            '--file-list', $path{'strict-regex-files.txt'},
            '--output', $strict_output, $option{baseline}, $leg->[1]],
        log => $path{"$leg->[0]-strict-regex-comparison.log"},
        commands => \@commands, statuses => \%statuses,
    );
    verify_comparison($strict_output, $strict_regex_expected_files, 0);
}
run_logged(
    name => 'packaging',
    command => [$option{perl}, $option{packaging_tool}, $option{jar}, $option{sbom}],
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
    },
    baseline => abs_file($option{baseline}),
    artifact_directory => abs_path($option{artifact_dir}),
    expected_files => $expected_files,
    strict_regex_expected_files => $strict_regex_expected_files,
    verified_runner_sha => $runner_sha,
    ledger_summary => $ledger->{summary},
    strict_regex_ledger_summary => $strict_regex_ledger->{summary},
    commands => \@commands,
    exit_statuses => \%statuses,
    artifacts => { map { $_ => { path => abs_file($path{$_}), sha256 => sha256_file($path{$_}) } } @retained },
};
write_json($path{'manifest.json'}, $manifest);
print "Phase 36 regex acceptance manifest: $path{'manifest.json'}\n";

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: run_phase36_regex_acceptance.pl --baseline FILE --artifact-dir DIR --jar FILE --sbom FILE [OPTIONS]

Compose the Phase 36 ledger, runner, comparator, and packaging gates into one
fail-closed immutable-artifact acceptance record. The current perl5 checkout is
recorded as provenance only, never compared to a pinned revision.

Options:
  --prepare-only             Execute the composition with explicitly injected
                             non-production tools and label it non-authoritative;
                             production defaults are rejected in this mode.
  --perl PATH --jperl PATH   Tool executables (default perl / ./jperl)
  --source-dir DIR           Clean source checkout to verify (default cwd)
  --perl5-dir DIR            Current imported perl5 checkout (default ./perl5)
  --timeout N --jobs N       Existing runner bounds and worker budget
  --version-timeout N        Hard bound for the jperl identity probe (default 30)
  --ledger-tool PATH --runner-tool PATH --comparator-tool PATH
  --ledger-scope MODE         complete (default) or regex-only discovery
  --packaging-tool PATH      Injectable list-form subprocess tools for testing
USAGE
    exit $status;
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
    my ($file, $expected_files, $allow_inherited_invalid) = @_;
    my $comparison = load_json($file, 'comparison');
    die "Comparison $file has no summary\n" unless ref $comparison->{summary} eq 'HASH';
    my $count = $comparison->{summary}{candidate_files};
    die "Comparison $file has file count drift\n"
        unless defined $count && $count == $expected_files;
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
