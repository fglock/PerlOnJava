#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path getcwd);
use Digest::SHA;
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Spec;
use Getopt::Long qw(GetOptions);
use JSON::PP;
use POSIX qw(setpgid strftime);
use Time::HiRes qw(time);

my %option = (
    policy => 'dev/tools/phase36_cpan_targets.json',
    version_timeout => 30,
);
my ($help, $jcpan_injected, $jperl_injected);
GetOptions(
    'manifest=s' => \$option{manifest},
    'policy=s' => \$option{policy},
    'evidence-dir=s' => \$option{evidence_dir},
    'jcpan=s' => sub { $option{jcpan} = $_[1]; $jcpan_injected = 1 },
    'jperl=s' => sub { $option{jperl} = $_[1]; $jperl_injected = 1 },
    'version-timeout=i' => \$option{version_timeout},
    'prepare-only!' => \$option{prepare_only},
    'resume!' => \$option{resume},
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;
for my $required (qw(manifest evidence_dir)) {
    die "--$required is required\n" unless defined $option{$required}
        && length $option{$required};
}
die "--version-timeout must be positive\n" unless $option{version_timeout} > 0;
die "--prepare-only requires explicitly injected --jcpan and --jperl\n"
    if $option{prepare_only} && (!$jcpan_injected || !$jperl_injected);

my $manifest = load_json($option{manifest}, 'acceptance manifest');
my $policy = load_json($option{policy}, 'target policy');
validate_policy($policy);
my $identity = validate_manifest($manifest);
my $inputs = $manifest->{inputs};
$option{jperl} //= $inputs->{jperl}{path};
$option{jcpan} //= $inputs->{jcpan}{path};
for my $pair ([$option{jperl}, 'jperl'], [$option{jcpan}, 'jcpan']) {
    die "$pair->[1] launcher is missing or not executable: $pair->[0]\n"
        unless -f $pair->[0] && -x $pair->[0];
}
die "injected jperl differs from manifest path\n"
    unless same_path($option{jperl}, $inputs->{jperl}{path});
die "injected jcpan differs from manifest path\n"
    unless same_path($option{jcpan}, $inputs->{jcpan}{path});

my $evidence = File::Spec->rel2abs($option{evidence_dir});
die "unsafe evidence directory: $evidence\n"
    if $evidence eq File::Spec->rootdir || $evidence eq ($ENV{HOME} // '');
make_path($evidence) unless -d $evidence;
die "evidence path is not a directory: $evidence\n" unless -d $evidence;
my %protected = protected_inputs($option{manifest}, $option{policy}, $inputs);
verify_protected(\%protected);
verify_checkout($inputs->{source}, $identity->{source_commit}, 'source');
verify_checkout($inputs->{perl5}, $identity->{perl5_commit}, 'perl5');
my $output = File::Spec->catfile($evidence, 'cpan-acceptance.json');
my @existing = directory_entries($evidence);
if (@existing) {
    die "Refusing nonempty evidence directory without --resume: $evidence\n"
        unless $option{resume};
    resume_existing($output, $policy, $identity, $inputs, $evidence, \%protected);
}
die "--resume requires retained evidence\n" if $option{resume} && !@existing;

my $version_log = File::Spec->catfile($evidence, 'jperl-version.log');
my $version_run = run_child(
    argv => [$option{jperl}, '-v'], log => $version_log,
    timeout => $option{version_timeout}, environment => {
        PERLONJAVA_JAR => $inputs->{jar}{path},
    });
die "jperl identity probe failed\n" unless !$version_run->{timeout}
    && !$version_run->{signal} && $version_run->{exit_code} == 0;
my $version_text = read_raw($version_log);
my @reported = $version_text =~ /\b([0-9a-f]{7,40})\b/ig;
die "jperl -v does not report runner/source commit\n"
    unless grep { index($identity->{runner_commit}, lc $_) == 0 } @reported;
verify_protected(\%protected);

my @targets = @{$policy->{expected_targets}};
my %policy_by_name = map { $_->{name} => $_ } @{$policy->{targets}};
my %results;
my @artifacts = ({
    path => relative_path($version_log, $evidence),
    sha256 => sha256_file($version_log), kind => 'jperl-version',
});
my $total_tests = 0;
for my $target (@targets) {
    my $target_policy = $policy_by_name{$target};
    my %modes;
    my $target_total = 0;
    for my $mode (@{$target_policy->{required_modes}}) {
        verify_protected(\%protected);
        verify_checkout($inputs->{source}, $identity->{source_commit}, 'source');
        verify_checkout($inputs->{perl5}, $identity->{perl5_commit}, 'perl5');
        my $slug = slug("$target-$mode");
        my $mode_dir = File::Spec->catdir($evidence, 'runs', $slug);
        my $home = File::Spec->catdir($mode_dir, 'home');
        my $tmp = File::Spec->catdir($mode_dir, 'tmp');
        make_path($home, $tmp);
        my $log = File::Spec->catfile($mode_dir, 'raw.log');
        my %environment = (
            PERLONJAVA_JAR => $inputs->{jar}{path},
            PERLONJAVA_HOME => $home,
            HOME => $home,
            TMPDIR => $tmp,
            PERL_MM_USE_DEFAULT => 1,
            JPERL_INTERPRETER => $mode eq 'interpreter' ? 1 : undef,
            PHASE36_CPAN_TARGET => $target,
            PHASE36_CPAN_MODE => $mode,
        );
        my @argv = ($option{jcpan}, '-t', $target);
        my $run = run_child(argv => \@argv, log => $log,
            timeout => $target_policy->{timeout_seconds},
            environment => \%environment);
        my $analysis = analyze_log($log, $target_policy);
        my $execution_error = $run->{exit_code} == 255
            && read_raw($log) =~ /Cannot execute/ ? 1 : 0;
        my $passed = !$run->{timeout} && !$run->{signal}
            && $run->{exit_code} == 0 && !$execution_error
            && !$analysis->{zero_tap} && !$analysis->{malformed}
            && !$analysis->{truncated} && !$analysis->{failures}
            && !@{$analysis->{unapproved_warnings}};
        my $meta = {
            target => $target, mode => $mode,
            status => $passed ? 'pass' : 'fail',
            argv => \@argv,
            environment => { map { $_ => $environment{$_} }
                qw(PERLONJAVA_JAR PERLONJAVA_HOME HOME TMPDIR PERL_MM_USE_DEFAULT
                    JPERL_INTERPRETER PHASE36_CPAN_TARGET PHASE36_CPAN_MODE) },
            environment_sha256 => Digest::SHA::sha256_hex(canonical({ map {
                $_ => $environment{$_} } qw(PERLONJAVA_JAR PERLONJAVA_HOME HOME
                    TMPDIR PERL_MM_USE_DEFAULT JPERL_INTERPRETER
                    PHASE36_CPAN_TARGET PHASE36_CPAN_MODE) })),
            started_at => $run->{started_at}, ended_at => $run->{ended_at},
            duration_seconds => $run->{duration_seconds},
            exit_code => $run->{exit_code}, signal => $run->{signal},
            timeout => boolean($run->{timeout}),
            execution_error => boolean($execution_error),
            total_tests => $analysis->{total_tests},
            failures => $analysis->{failures}, skips => $analysis->{skips},
            zero_tap => boolean($analysis->{zero_tap}),
            malformed => boolean($analysis->{malformed}),
            truncated => boolean($analysis->{truncated}),
            warning_diagnostics => $analysis->{warning_diagnostics},
            unapproved_warnings => $analysis->{unapproved_warnings},
            raw_log => { path => relative_path($log, $evidence),
                sha256 => sha256_file($log) },
            identity => { %$identity, jar_path => $inputs->{jar}{path},
                sbom_path => $inputs->{sbom}{path} },
        };
        my $meta_path = File::Spec->catfile($mode_dir, 'result.json');
        write_json($meta_path, $meta);
        push @artifacts,
            { path => relative_path($log, $evidence), sha256 => sha256_file($log), kind => 'raw-log' },
            { path => relative_path($meta_path, $evidence), sha256 => sha256_file($meta_path), kind => 'mode-result' };
        $modes{$mode} = $meta;
        $target_total += $analysis->{total_tests};
    }
    my $target_pass = !grep { $modes{$_}{status} ne 'pass' } keys %modes;
    $results{$target} = {
        status => $target_pass ? 'pass' : 'fail',
        total_tests => $target_total,
        timeout => boolean(grep { $modes{$_}{timeout} } keys %modes),
        truncated => boolean(grep { $modes{$_}{truncated} || $modes{$_}{malformed} } keys %modes),
        execution_error => boolean(grep { $modes{$_}{execution_error} } keys %modes),
        rationale => $target_policy->{rationale},
        focused_selector_permitted => boolean($target_policy->{focused_selector_permitted}),
        modes => \%modes,
    };
    $total_tests += $target_total;
}
verify_protected(\%protected);
verify_checkout($inputs->{source}, $identity->{source_commit}, 'source');
verify_checkout($inputs->{perl5}, $identity->{perl5_commit}, 'perl5');
my $all_pass = !grep { $results{$_}{status} ne 'pass' } @targets;
my $document = {
    schema_version => 1,
    mode => $option{prepare_only} ? 'prepare-only' : 'acceptance',
    status => $all_pass ? 'pass' : 'fail',
    expected_targets => \@targets,
    results => \%results,
    total_tests => $total_tests,
    excluded_audits => [],
    identity => { %$identity,
        manifest_sha256 => $protected{manifest}{sha256},
        policy_sha256 => $protected{policy}{sha256},
        jcpan_sha256 => $protected{jcpan}{sha256},
        inputs => $inputs,
    },
    artifacts => \@artifacts,
};
write_json($output, $document);
write_raw("$output.sha256", sha256_file($output) . "  cpan-acceptance.json\n");
print "Phase 36 CPAN acceptance: $output\n";
exit($all_pass ? 0 : 1);

sub validate_manifest {
    my ($manifest) = @_;
    die "Acceptance manifest schema_version must be 1\n"
        unless ($manifest->{schema_version} // 0) == 1;
    die "Acceptance manifest mode must be acceptance\n"
        unless ($manifest->{mode} // '') eq 'acceptance';
    my $identity = $manifest->{identity};
    die "Acceptance manifest identity is missing\n" unless ref $identity eq 'HASH';
    for my $field (qw(source_commit runner_commit perl5_commit)) {
        die "Acceptance manifest $field is not a full Git SHA\n"
            unless ($identity->{$field} // '') =~ /\A[0-9a-f]{40}\z/;
    }
    die "Acceptance manifest runner commit differs from source commit\n"
        unless $identity->{runner_commit} eq $identity->{source_commit};
    for my $field (qw(jperl_sha256 jar_sha256 sbom_sha256)) {
        die "Acceptance manifest $field is not SHA-256\n"
            unless ($identity->{$field} // '') =~ /\A[0-9a-f]{64}\z/;
    }
    my $inputs = $manifest->{inputs};
    die "Acceptance manifest inputs are missing\n" unless ref $inputs eq 'HASH';
    for my $name (qw(source perl5 jperl jcpan jar sbom)) {
        die "Acceptance manifest input $name is missing\n"
            unless ref $inputs->{$name} eq 'HASH' && length($inputs->{$name}{path} // '');
    }
    die "Manifest jperl hash differs from identity\n"
        unless ($inputs->{jperl}{sha256} // '') eq $identity->{jperl_sha256};
    die "Manifest JAR hash differs from identity\n"
        unless ($inputs->{jar}{sha256} // '') eq $identity->{jar_sha256};
    die "Manifest SBOM hash differs from identity\n"
        unless ($inputs->{sbom}{sha256} // '') eq $identity->{sbom_sha256};
    return { map { $_ => $identity->{$_} }
        qw(source_commit runner_commit perl5_commit jperl_sha256 jar_sha256 sbom_sha256) };
}

sub validate_policy {
    my ($policy) = @_;
    die "Target policy schema_version must be 1\n"
        unless ($policy->{schema_version} // 0) == 1;
    die "Target policy expected_targets is empty\n"
        unless ref($policy->{expected_targets}) eq 'ARRAY' && @{$policy->{expected_targets}};
    die "Target policy targets is empty\n"
        unless ref($policy->{targets}) eq 'ARRAY' && @{$policy->{targets}};
    my (%expected, %actual);
    for my $name (@{$policy->{expected_targets}}) {
        die "Target policy has invalid or duplicate expected target\n"
            unless defined($name) && !ref($name) && length($name) && !$expected{$name}++;
    }
    for my $target (@{$policy->{targets}}) {
        die "Target policy entry is malformed\n" unless ref $target eq 'HASH';
        my $name = $target->{name} // '';
        die "Target policy has invalid or duplicate target entry\n"
            unless length($name) && !$actual{$name}++;
        die "Target $name has no rationale\n" unless length($target->{rationale} // '');
        die "Target $name has invalid timeout\n"
            unless ($target->{timeout_seconds} // 0) =~ /\A\d+\z/
                && $target->{timeout_seconds} > 0;
        die "Target $name has no required modes\n"
            unless ref($target->{required_modes}) eq 'ARRAY' && @{$target->{required_modes}};
        my %mode;
        for (@{$target->{required_modes}}) {
            die "Target $name has invalid or duplicate mode\n"
                unless /\A(?:jvm|interpreter)\z/ && !$mode{$_}++;
        }
        die "Target $name must require JVM and interpreter\n"
            unless canonical([sort keys %mode]) eq canonical([qw(interpreter jvm)]);
        die "Target $name focused selector policy is missing\n"
            unless JSON::PP::is_bool($target->{focused_selector_permitted});
        die "Target $name approved warnings must be an array\n"
            unless ref($target->{approved_warning_patterns}) eq 'ARRAY';
        eval { qr/$_/ for @{$target->{approved_warning_patterns}} };
        die "Target $name has invalid approved warning pattern\n" if $@;
    }
    die "Target policy expected/result set drift\n"
        unless canonical([sort keys %expected]) eq canonical([sort keys %actual]);
}

sub protected_inputs {
    my ($manifest, $policy, $inputs) = @_;
    my %files = (manifest => $manifest, policy => $policy,
        jperl => $inputs->{jperl}{path}, jcpan => $inputs->{jcpan}{path},
        jar => $inputs->{jar}{path}, sbom => $inputs->{sbom}{path});
    my %protected;
    for my $name (keys %files) {
        die "Protected input is missing or empty: $files{$name}\n"
            unless -f $files{$name} && -s $files{$name};
        $protected{$name} = { path => abs_path($files{$name}), sha256 => sha256_file($files{$name}) };
    }
    for my $pair ([jperl => 'jperl'], [jar => 'jar'], [sbom => 'sbom']) {
        my ($name, $input) = @$pair;
        die "Protected $name hash differs from manifest\n"
            unless $protected{$name}{sha256} eq $inputs->{$input}{sha256};
    }
    die "Protected jcpan hash differs from manifest\n"
        unless $protected{jcpan}{sha256} eq ($inputs->{jcpan}{sha256} // '');
    return %protected;
}

sub verify_protected {
    my ($protected) = @_;
    for my $name (sort keys %$protected) {
        my $item = $protected->{$name};
        die "Protected input disappeared during execution: $name\n"
            unless -f $item->{path};
        die "Protected input mutated during execution: $name\n"
            unless sha256_file($item->{path}) eq $item->{sha256};
    }
}

sub verify_checkout {
    my ($descriptor, $expected, $label) = @_;
    die "$label checkout descriptor commit differs from identity\n"
        unless ($descriptor->{commit} // '') eq $expected;
    my $actual = capture(['git', '-C', $descriptor->{path}, 'rev-parse', 'HEAD']);
    $actual =~ s/\s+\z//;
    die "$label checkout commit mismatch\n" unless $actual eq $expected;
    my $status = capture(['git', '-C', $descriptor->{path}, 'status', '--porcelain', '--untracked-files=no']);
    die "$label checkout tracked state is dirty\n" if length $status;
}

sub analyze_log {
    my ($log, $target_policy) = @_;
    my $text = read_raw($log);
    my @summaries = $text =~ /^Files=\d+,\s+Tests=(\d+)\b/mg;
    my $tests = @summaries ? 0 + $summaries[-1] : 0;
    my $failures = () = $text =~ /^\s*not ok\b/mg;
    my $skips = () = $text =~ /^\s*ok\b[^\n]*#\s*skip\b/img;
    my @warnings = grep {
        my $line = $_;
        $line !~ /^\s*(?:ok|not ok|#)/i
            && $line =~ /(?:Use of uninitialized|uninitialized value|Argument .* isn't numeric|Possible unintended interpolation|Wide character in|Subroutine .* redefined|WARNING:|warning:|\bat\s+\S.*\s+line\s+\d+\.?\s*$)/i
    } split /\n/, $text;
    my @approved = @{$target_policy->{approved_warning_patterns}};
    my @unapproved = grep {
        my $line = $_;
        !grep { $line =~ /$_/ } @approved;
    } @warnings;
    my $tap_lines = () = $text =~ /^\s*(?:not )?ok\b/mg;
    my $truncated = !@summaries && $tap_lines ? 1 : 0;
    my $malformed = !@summaries
        || $text =~ /(?:Parse errors|Bad plan|No plan found|Tests out of sequence)/i
        ? 1 : 0;
    return { total_tests => $tests, failures => $failures, skips => $skips,
        zero_tap => $tests == 0 ? 1 : 0, malformed => $malformed,
        truncated => $truncated, warning_diagnostics => \@warnings,
        unapproved_warnings => \@unapproved };
}

sub run_child {
    my (%arg) = @_;
    my $started = time;
    my $started_at = timestamp();
    my $pid = fork();
    die "Cannot fork $arg{argv}[0]: $!\n" unless defined $pid;
    if ($pid == 0) {
        eval { setpgid(0, 0) };
        open STDOUT, '>:raw', $arg{log} or die "Cannot write $arg{log}: $!\n";
        open STDERR, '>&', \*STDOUT or die "Cannot redirect stderr: $!\n";
        for my $key (keys %{$arg{environment} // {}}) {
            defined $arg{environment}{$key}
                ? ($ENV{$key} = $arg{environment}{$key}) : delete $ENV{$key};
        }
        exec { $arg{argv}[0] } @{$arg{argv}} or do {
            print STDERR "Cannot execute $arg{argv}[0]: $!\n";
            POSIX::_exit(255);
        };
    }
    my $parent_group_ready = eval { setpgid($pid, $pid); 1 } ? 1 : 0;
    my ($raw, $timed_out);
    my $completed = eval {
        local $SIG{ALRM} = sub { die "timeout\n" };
        alarm $arg{timeout};
        waitpid($pid, 0);
        $raw = $?;
        alarm 0;
        1;
    };
    if (!$completed) {
        alarm 0;
        $timed_out = 1;
        my $group = eval { getpgrp($pid) };
        my $owns_group = defined($group) && $group == $pid;
        $owns_group = 1 if !$owns_group && $parent_group_ready;
        my $kill_target = $owns_group ? -$pid : $pid;
        kill 'TERM', $kill_target;
        select undef, undef, undef, 0.1;
        kill 'KILL', $kill_target if kill 0, $pid;
        waitpid($pid, 0);
        $raw = $?;
    }
    my $signal = $raw & 127;
    my $exit = $signal ? 0 : ($raw >> 8);
    return { started_at => $started_at, ended_at => timestamp(),
        duration_seconds => 0 + sprintf('%.6f', time - $started),
        exit_code => $exit, signal => $signal, timeout => $timed_out ? 1 : 0 };
}

sub resume_existing {
    my ($output, $policy, $identity, $inputs, $evidence, $protected) = @_;
    die "Safe resume requires retained cpan-acceptance.json\n" unless -f $output;
    my $seal = "$output.sha256";
    die "Safe resume requires retained cpan-acceptance.json.sha256\n" unless -f $seal;
    my $seal_text = read_raw($seal);
    my ($sealed_sha) = $seal_text =~ /\A([0-9a-f]{64})\b/;
    die "Retained acceptance manifest seal is malformed\n" unless $sealed_sha;
    die "Retained acceptance manifest hash mismatch\n"
        unless sha256_file($output) eq $sealed_sha;
    my $old = load_json($output, 'retained CPAN acceptance');
    die "Retained target policy drift\n"
        unless canonical($old->{expected_targets}) eq canonical($policy->{expected_targets});
    for my $field (qw(source_commit runner_commit perl5_commit jperl_sha256 jar_sha256 sbom_sha256)) {
        die "Retained identity drift: $field\n"
            unless ($old->{identity}{$field} // '') eq ($identity->{$field} // '');
    }
    for my $field (qw(manifest policy jcpan)) {
        my $recorded = $old->{identity}{"${field}_sha256"} // '';
        die "Retained input identity drift: $field\n"
            unless $recorded eq $protected->{$field}{sha256};
    }
    die "Retained input descriptors drift\n"
        unless canonical($old->{identity}{inputs}) eq canonical($inputs);
    my %expected = ('jperl-version.log' => 'jperl-version');
    my %policy_by_name = map { $_->{name} => $_ } @{$policy->{targets}};
    for my $target (@{$policy->{expected_targets}}) {
        for my $mode (@{$policy_by_name{$target}{required_modes}}) {
            my $base = File::Spec->catfile('runs', slug("$target-$mode"));
            $expected{File::Spec->catfile($base, 'raw.log')} = 'raw-log';
            $expected{File::Spec->catfile($base, 'result.json')} = 'mode-result';
        }
    }
    my %retained;
    for my $artifact (@{$old->{artifacts} // []}) {
        die "Retained artifact descriptor is malformed\n"
            unless ref($artifact) eq 'HASH' && length($artifact->{path} // '')
                && ($artifact->{sha256} // '') =~ /\A[0-9a-f]{64}\z/;
        my $relative = $artifact->{path};
        die "Retained artifact path is unsafe: $relative\n"
            if File::Spec->file_name_is_absolute($relative)
                || grep { $_ eq '..' } File::Spec->splitdir($relative);
        die "Retained artifact is unexpected or has wrong kind: $relative\n"
            unless defined($expected{$relative})
                && ($artifact->{kind} // '') eq $expected{$relative}
                && !$retained{$relative}++;
        my $path = File::Spec->catfile($evidence, File::Spec->splitdir($relative));
        die "Retained artifact missing: $artifact->{path}\n" unless -f $path;
        my $resolved = abs_path($path);
        my $resolved_root = abs_path($evidence);
        die "Retained artifact resolves outside evidence root: $relative\n"
            unless defined($resolved) && defined($resolved_root)
                && index($resolved, "$resolved_root/") == 0;
        die "Retained artifact hash mismatch: $artifact->{path}\n"
            unless sha256_file($path) eq $artifact->{sha256};
    }
    my @missing = sort grep { !$retained{$_} } keys %expected;
    die "Retained artifact set is incomplete: @missing\n" if @missing;
    validate_retained_results($old, $policy, $identity, $inputs,
        $evidence, $protected);
    print "Safe resume verified retained evidence: $output\n";
    exit(($old->{status} // '') eq 'pass' ? 0 : 1);
}

sub validate_retained_results {
    my ($old, $policy, $identity, $inputs, $evidence, $protected) = @_;
    my %policy_by_name = map { $_->{name} => $_ } @{$policy->{targets}};
    my $results = $old->{results};
    die "Retained result set drift\n" unless ref($results) eq 'HASH'
        && canonical([sort keys %$results])
            eq canonical([sort @{$policy->{expected_targets}}]);

    my $all_total = 0;
    my $all_pass = 1;
    for my $target (@{$policy->{expected_targets}}) {
        my $target_policy = $policy_by_name{$target};
        my $target_result = $results->{$target};
        die "Retained target result is malformed: $target\n"
            unless ref($target_result) eq 'HASH'
                && ref($target_result->{modes}) eq 'HASH';
        my @required_modes = sort @{$target_policy->{required_modes}};
        my @retained_modes = sort keys %{$target_result->{modes}};
        die "Retained mode set drift: $target\n"
            unless canonical(\@retained_modes) eq canonical(\@required_modes);

        my ($target_total, $target_pass, $target_timeout,
            $target_truncated, $target_execution_error) = (0, 1, 0, 0, 0);
        for my $mode (@required_modes) {
            my $base = File::Spec->catfile('runs', slug("$target-$mode"));
            my $raw_relative = File::Spec->catfile($base, 'raw.log');
            my $meta_relative = File::Spec->catfile($base, 'result.json');
            my $raw_path = File::Spec->catfile($evidence,
                File::Spec->splitdir($raw_relative));
            my $meta_path = File::Spec->catfile($evidence,
                File::Spec->splitdir($meta_relative));
            my $meta = load_json($meta_path, 'retained mode result');
            die "Retained mode result differs from aggregate: $target $mode\n"
                unless canonical($meta)
                    eq canonical($target_result->{modes}{$mode});
            die "Retained mode result identity mismatch: $target $mode\n"
                unless ($meta->{target} // '') eq $target
                    && ($meta->{mode} // '') eq $mode;

            my $argv = $meta->{argv};
            die "Retained mode command mismatch: $target $mode\n"
                unless ref($argv) eq 'ARRAY' && @$argv == 3
                    && same_path($argv->[0], $protected->{jcpan}{path})
                    && $argv->[1] eq '-t' && $argv->[2] eq $target;
            my $environment = $meta->{environment};
            my $mode_dir = File::Spec->catdir($evidence, $base);
            my $home = File::Spec->catdir($mode_dir, 'home');
            my $tmp = File::Spec->catdir($mode_dir, 'tmp');
            die "Retained mode environment mismatch: $target $mode\n"
                unless ref($environment) eq 'HASH'
                    && ($environment->{PERLONJAVA_JAR} // '') eq $inputs->{jar}{path}
                    && ($environment->{PERLONJAVA_HOME} // '') eq $home
                    && ($environment->{HOME} // '') eq $home
                    && ($environment->{TMPDIR} // '') eq $tmp
                    && ($environment->{PERL_MM_USE_DEFAULT} // '') eq '1'
                    && ($environment->{PHASE36_CPAN_TARGET} // '') eq $target
                    && ($environment->{PHASE36_CPAN_MODE} // '') eq $mode
                    && ($mode eq 'interpreter'
                        ? (($environment->{JPERL_INTERPRETER} // '') eq '1')
                        : (exists($environment->{JPERL_INTERPRETER})
                            && !defined($environment->{JPERL_INTERPRETER})));
            my @environment_keys = qw(PERLONJAVA_JAR PERLONJAVA_HOME HOME TMPDIR
                PERL_MM_USE_DEFAULT JPERL_INTERPRETER PHASE36_CPAN_TARGET
                PHASE36_CPAN_MODE);
            die "Retained mode environment hash mismatch: $target $mode\n"
                unless ($meta->{environment_sha256} // '') eq
                    Digest::SHA::sha256_hex(canonical({ map {
                        $_ => $environment->{$_} } @environment_keys }));

            my $expected_mode_identity = { %$identity,
                jar_path => $inputs->{jar}{path},
                sbom_path => $inputs->{sbom}{path} };
            die "Retained mode artifact identity mismatch: $target $mode\n"
                unless canonical($meta->{identity})
                    eq canonical($expected_mode_identity);
            die "Retained raw log descriptor mismatch: $target $mode\n"
                unless ref($meta->{raw_log}) eq 'HASH'
                    && ($meta->{raw_log}{path} // '') eq $raw_relative
                    && ($meta->{raw_log}{sha256} // '') eq sha256_file($raw_path);

            my $analysis = analyze_log($raw_path, $target_policy);
            my $raw_text = read_raw($raw_path);
            my $execution_error = ($meta->{exit_code} // 0) == 255
                && $raw_text =~ /Cannot execute/ ? 1 : 0;
            my $passed = !$meta->{timeout} && !$meta->{signal}
                && ($meta->{exit_code} // -1) == 0 && !$execution_error
                && !$analysis->{zero_tap} && !$analysis->{malformed}
                && !$analysis->{truncated} && !$analysis->{failures}
                && !@{$analysis->{unapproved_warnings}};
            my $retained_analysis = {
                total_tests => $meta->{total_tests}, failures => $meta->{failures},
                skips => $meta->{skips}, zero_tap => $meta->{zero_tap},
                malformed => $meta->{malformed}, truncated => $meta->{truncated},
                warning_diagnostics => $meta->{warning_diagnostics},
                unapproved_warnings => $meta->{unapproved_warnings},
            };
            my $recomputed_analysis = {
                total_tests => $analysis->{total_tests}, failures => $analysis->{failures},
                skips => $analysis->{skips}, zero_tap => boolean($analysis->{zero_tap}),
                malformed => boolean($analysis->{malformed}),
                truncated => boolean($analysis->{truncated}),
                warning_diagnostics => $analysis->{warning_diagnostics},
                unapproved_warnings => $analysis->{unapproved_warnings},
            };
            die "Retained mode analysis mismatch: $target $mode\n"
                unless canonical($retained_analysis) eq canonical($recomputed_analysis)
                    && !!$meta->{execution_error} == !!$execution_error
                    && ($meta->{status} // '') eq ($passed ? 'pass' : 'fail');

            $target_total += $analysis->{total_tests};
            $target_pass = 0 unless $passed;
            $target_timeout ||= !!$meta->{timeout};
            $target_truncated ||= $analysis->{truncated} || $analysis->{malformed};
            $target_execution_error ||= $execution_error;
        }
        my $expected_target = {
            status => $target_pass ? 'pass' : 'fail',
            total_tests => $target_total,
            timeout => boolean($target_timeout),
            truncated => boolean($target_truncated),
            execution_error => boolean($target_execution_error),
            rationale => $target_policy->{rationale},
            focused_selector_permitted => boolean($target_policy->{focused_selector_permitted}),
            modes => $target_result->{modes},
        };
        die "Retained target aggregate mismatch: $target\n"
            unless canonical($target_result) eq canonical($expected_target);
        $all_total += $target_total;
        $all_pass = 0 unless $target_pass;
    }
    die "Retained aggregate analysis mismatch\n"
        unless ($old->{total_tests} // -1) == $all_total
            && ($old->{status} // '') eq ($all_pass ? 'pass' : 'fail');
}

sub directory_entries {
    my ($dir) = @_;
    opendir my $dh, $dir or die "Cannot read evidence directory $dir: $!\n";
    my @entries = grep { $_ ne '.' && $_ ne '..' } readdir $dh;
    closedir $dh;
    return @entries;
}
sub load_json { my ($p,$l)=@_; my $r=read_raw($p); my $d=eval{JSON::PP->new->utf8->decode($r)}; die "Invalid $l JSON in $p\n" unless ref($d) eq 'HASH'; return $d }
sub write_json { my ($p,$d)=@_; make_path(dirname($p)); open my $f,'>:raw',$p or die $!; print {$f} JSON::PP->new->canonical->pretty->encode($d); close $f or die $! }
sub write_raw { my ($p,$c)=@_; open my $f,'>:raw',$p or die "Cannot write $p: $!\n"; print {$f} $c; close $f or die "Cannot close $p: $!\n" }
sub read_raw { my ($p)=@_; open my $f,'<:raw',$p or die "Cannot read $p: $!\n"; local $/; my $r=<$f>; close $f; return $r }
sub sha256_file { my ($p)=@_; open my $f,'<:raw',$p or die $!; my $s=Digest::SHA->new(256); $s->addfile($f); close $f; return $s->hexdigest }
sub capture { my ($a)=@_; open my $f,'-|',@$a or die "Cannot execute $a->[0]: $!\n"; local $/; my $r=<$f>; close $f or die "Command $a->[0] failed\n"; return $r }
sub canonical { JSON::PP->new->canonical->encode($_[0]) }
sub boolean { $_[0] ? JSON::PP::true : JSON::PP::false }
sub slug { my $s=lc $_[0]; $s =~ s/[^a-z0-9]+/-/g; $s =~ s/^-|-$//g; return $s }
sub same_path { (abs_path($_[0]) // '') eq (abs_path($_[1]) // '') }
sub relative_path { File::Spec->abs2rel($_[0], $_[1]) }
sub timestamp { strftime('%Y-%m-%dT%H:%M:%SZ', gmtime()) }
sub usage {
    print <<'USAGE';
Usage: run_phase36_cpan_acceptance.pl --manifest FILE --evidence-dir DIR [OPTIONS]

Run the immutable Phase 36 affected-CPAN target policy. The acceptance manifest
must contain identity.{source_commit,runner_commit,perl5_commit,jperl_sha256,
jar_sha256,sbom_sha256} and inputs descriptors for source, perl5, jperl, jcpan,
jar, and sbom. File descriptors contain absolute path and sha256; checkout
descriptors contain absolute path and commit.

Options:
  --policy FILE       Checked-in target policy (default phase36_cpan_targets.json)
  --resume            Verify and reuse a complete sealed evidence directory
  --prepare-only      Non-authoritative control-plane run; requires explicitly
                      injected --jcpan and --jperl fake launchers
  --version-timeout N Bound the jperl -v identity probe (default 30 seconds)

Fake launchers may inspect PHASE36_CPAN_TARGET and PHASE36_CPAN_MODE. Every
child receives the manifest JAR through PERLONJAVA_JAR and an isolated HOME,
PERLONJAVA_HOME, and TMPDIR beneath the evidence directory.
USAGE
    exit $_[0];
}
