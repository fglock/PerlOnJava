#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use Fcntl qw(F_GETFL F_SETFL O_CREAT O_EXCL O_NONBLOCK O_WRONLY);
use File::Basename qw(basename dirname);
use File::Find qw(find);
use File::Path qw(remove_tree);
use File::Spec;
use Getopt::Long qw(GetOptions);
use IO::Handle;
use IO::Select;
use JSON::PP;
use POSIX qw(WNOHANG strftime);
use Time::HiRes qw(sleep time);

my $SCHEMA = 'perlonjava.regex_implementation.make-evidence/v1';
my $CLASSIFIER_VERSION = 'regex_implementation-make-warning-failure/v1';
my $MAX_TIMEOUT = 7_200;
my $MAX_LOG = 32 * 1024 * 1024;
my $MAX_CAPTURE = 1024 * 1024;
my $MAX_INPUT = 64 * 1024 * 1024;
my $MAX_JAR = 512 * 1024 * 1024;
my $MAX_JSON = 8 * 1024 * 1024;
my $MAX_GENERATED_FILES = 100_000;
my $MAX_GENERATED_BYTES = 2 * 1024 * 1024 * 1024;
my $MAX_EMBEDDED_CAPTURE = 128 * 1024 * 1024;
my %option = (mode => 'acceptance', timeout => 1800);
my $capture_sequence = 0;
my $pending_signal;

Getopt::Long::Configure(qw(no_auto_abbrev no_ignore_case no_getopt_compat
    no_bundling no_pass_through));
validate_cli_tokens(\@ARGV);
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
die "Windows acceptance is fail-closed: bounded process-tree cleanup is not "
    . "authenticated on this platform\n" if $^O eq 'MSWin32';

my $ok = eval { produce(); 1 };
my $error = $@;
if (!$ok && $option{mode} eq 'report') {
    my $report = {
        schema => 'perlonjava.regex_implementation.make-evidence-report/v1',
        schema_version => 1,
        kind => 'make-report',
        producer => 'run_make_evidence.pl',
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
    $tool{jar_tool} = trusted_archive_tool();
    die "The running Perl does not match --perl\n"
        unless abs_path($^X) eq $tool{perl}{path}
            && sha256_file(abs_path($^X), $MAX_INPUT, 'running Perl') eq $tool{perl}{sha256};

    my $producer = strict_source_file(File::Spec->rel2abs($0), $source,
        'producer', $tool{git}{path});
    die "Producer basename is not authoritative\n"
        unless basename($producer->{path}) eq 'run_make_evidence.pl';
    my %input = (
        gradlew => authority_source_file($source, 'gradlew', 'gradlew'),
        gradle_wrapper_jar => authority_source_file($source,
            'gradle wrapper JAR', 'gradle/wrapper/gradle-wrapper.jar'),
        gradle_wrapper_properties => authority_source_file($source,
            'Gradle wrapper properties', 'gradle/wrapper/gradle-wrapper.properties'),
    );
    for my $entry ([makefile => 'Makefile'], [build_gradle => 'build.gradle'],
            [settings_gradle => 'settings.gradle']) {
        $input{$entry->[0]} = strict_source_file(
            File::Spec->catfile($source, split m{/}, $entry->[1]),
            $source, $entry->[0], $tool{git}{path});
    }
    die "gradlew is not executable\n" unless -x $input{gradlew}{path};

    my $source_before = source_identity($source, $tool{git}{path}, \%input);
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
        jar_tool => capture_bounded([$tool{jar_tool}{path}, '-v'],
            'trusted archive tool version'),
        gradle_wrapper => capture_bounded([$input{gradlew}{path}, '--version'], 'Gradle wrapper version'),
    );
    verify_identities(\%tool, \%input, $producer);
    my $versions_bytes = canonical_pretty(\%version);

    my @path = unique(map { dirname($tool{$_}{path}) }
        qw(make java shell git perl jar_tool));
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
        qw(log before after versions jar_version embedded json seal);
    my %final = (
        log => "$output.make.log", before => "$output.source-before.json",
        after => "$output.source-after.json", versions => "$output.tool-versions.json",
        jar_version => "$output.jar-version.log",
        embedded => "$output.jar-embedded.json", json => $output,
        seal => "$output.seal",
    );
    for my $path (values %stage, values %final) {
        die "Publication path already exists: $path\n" if -e $path || -l $path;
    }

    my @argv = ($tool{make}{path});
    my ($success, $failure, $authority_published, $authority_durable)
        = (0, undef, 0, 0);
    my $published = [];
    local @SIG{qw(INT TERM HUP QUIT)} = map {
        my $signal = $_;
        sub {
            $pending_signal = $signal;
            die "Evidence capture interrupted by $signal\n";
        }
    } qw(INT TERM HUP QUIT);
    eval {
        check_interrupted();
        write_exclusive($stage{before}, canonical_pretty($source_before),
            $MAX_JSON, 'source-before stage');
        write_exclusive($stage{versions}, $versions_bytes, $MAX_JSON, 'tool-version stage');
        my $run = run_bounded(\@argv, $source, $environment, $stage{log},
            $option{timeout}, $MAX_LOG, 'make');
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

        my $source_after = source_identity($source, $tool{git}{path}, \%input);
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
            $option{expected_source_commit}, 'JAR runtime version output');
        my ($embedded_commit, $embedded_evidence) = authenticate_embedded_commit(
            $jar, $jar_after, $tool{jar_tool}, $option{expected_source_commit});
        die "Runtime and embedded JAR commits disagree\n"
            unless $reported_commit eq $embedded_commit;
        write_exclusive($stage{embedded}, canonical_pretty($embedded_evidence),
            $MAX_JSON, 'embedded JAR evidence stage');

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
            jar_embedded => descriptor($final{embedded}, $stage{embedded}),
        );
        my $classifier_sha = sha256_hex(join("\n", $CLASSIFIER_VERSION,
            warning_patterns(), failure_patterns()));
        my $started_utc = utc($run->{started_epoch});
        my $finished_utc = utc($run->{finished_epoch});
        my %public_tools = map {
            $_ => public_tool($tool{$_}, $version{$_})
        } keys %tool;
        my $payload = {
            schema => $SCHEMA, schema_version => 1, kind => 'make',
            producer => 'run_make_evidence.pl', mode => 'acceptance',
            status => 'pass', verified => JSON::PP::true,
            authoritative => JSON::PP::true,
            identity => {
                source_commit => $source_before->{head},
                runner_commit => $option{expected_runner_commit},
                jar_sha256 => $jar_after->{sha256},
                jar_reported_commit => $reported_commit,
                jar_embedded_commit => $embedded_commit,
            },
            source => { root => $source, before => $source_before,
                after => $source_after },
            command => { cwd => $source, argv => \@argv,
                environment => $environment, started_utc => $started_utc,
                finished_utc => $finished_utc,
                duration_milliseconds => int($run->{duration_seconds} * 1000) },
            tools => {
                %public_tools,
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
        if ($option{mode} eq 'report') {
            my $report = {
                schema => 'perlonjava.regex_implementation.make-evidence-report/v1',
                schema_version => 1, kind => 'make-report',
                producer => 'run_make_evidence.pl', mode => 'report',
                status => 'pass', verified => JSON::PP::false,
                authoritative => JSON::PP::false,
                identity => $payload->{identity}, completion => $payload->{completion},
            };
            write_exclusive($output, canonical_pretty($report), $MAX_JSON,
                'successful report');
            $success = 1;
        } else {
        my $payload_sha = sha256_hex(canonical($payload));
        my $document = { %$payload, seal => {
            algorithm => 'SHA-256', payload_sha256 => $payload_sha } };
        my $json = canonical_pretty($document);
        my $seal = "SHA-256 $payload_sha " . sha256_hex($json) . "\n";
        write_exclusive($stage{json}, $json, $MAX_JSON, 'JSON stage');
        write_exclusive($stage{seal}, $seal, 512, 'seal stage');

        assert_document_schema($document);
        verify_publication_boundary($source, $source_after, \%tool, \%input,
            $producer, $jar_after);
        verify_stat_identity($stage{log}, $log_pin, 'make log');
        check_interrupted();
        verify_output_parent();
        for my $name (qw(log before after versions jar_version embedded seal)) {
            check_interrupted();
            publish_link($stage{$name}, $final{$name}, $published);
            verify_hardlink_publication($stage{$name}, $final{$name},
                $name eq 'log' ? $MAX_LOG : $MAX_JSON, "$name artifact");
        }
        sync_directory($output_parent, 'output parent after sidecar publication');
        publication_failpoint('after-sidecar-directory-sync');
        my $published_log_pin = stat_identity($final{log}, 'published make log');
        verify_publication_boundary($source, $source_after, \%tool, \%input,
            $producer, $jar_after);
        verify_stat_identity($stage{log}, $published_log_pin, 'staged make log');
        verify_stat_identity($final{log}, $published_log_pin, 'published make log');
        for my $name (qw(before after versions jar_version embedded seal)) {
            verify_hardlink_publication($stage{$name}, $final{$name}, $MAX_JSON,
                "$name artifact at authority boundary");
        }
        for my $name (qw(log before after versions jar_version embedded seal)) {
            unlink $stage{$name}
                or die "Cannot remove durable $name staging link: $!\n";
        }
        sync_directory($output_parent, 'output parent after staging cleanup');
        verify_published_descriptor($final{log}, $artifact{make_log}, $MAX_LOG,
            'make log before authority');
        verify_published_descriptor($final{before}, $artifact{source_before},
            $MAX_JSON, 'source-before before authority');
        verify_published_descriptor($final{after}, $artifact{source_after},
            $MAX_JSON, 'source-after before authority');
        verify_published_descriptor($final{versions}, $artifact{tool_versions},
            $MAX_JSON, 'tool versions before authority');
        verify_published_descriptor($final{jar_version}, $artifact{jar_version},
            $MAX_CAPTURE, 'JAR version before authority');
        verify_published_descriptor($final{embedded}, $artifact{jar_embedded},
            $MAX_JSON, 'embedded JAR evidence before authority');
        die "Seal mutated before authority publication\n"
            unless sha256_file($final{seal}, 512, 'seal before authority')
                eq sha256_hex($seal);
        check_interrupted();
        publish_authority_link($stage{json}, $output, $published);
        $authority_published = 1;
        verify_hardlink_publication($stage{json}, $output, $MAX_JSON,
            'authoritative JSON');
        publication_failpoint('after-authority-link');
        unlink $stage{json}
            or die "Cannot remove authoritative JSON staging link: $!\n";
        sync_directory($output_parent, 'output parent after authority publication');
        publication_failpoint('after-authority-directory-sync');
        $authority_durable = 1;
        $success = $authority_durable;
        $SIG{$_} = 'IGNORE' for qw(INT TERM HUP QUIT);
        }
        1;
    } or $failure = $@;
    unless ($success) {
        local @SIG{qw(INT TERM HUP QUIT)} = ('IGNORE') x 4;
        my @remove = grep { -e $_ || -l $_ } reverse @$published;
        for my $path (@remove) {
            unlink $path or $failure .= "Cannot roll back publication $path: $!\n";
        }
        for my $path (grep { -e $_ || -l $_ } values %stage) {
            unlink $path or $failure .= "Cannot remove staging path $path: $!\n";
        }
        my $sync_ok = eval {
            sync_directory($output_parent,
                'output parent after failed publication rollback'); 1;
        };
        $failure .= $@ unless $sync_ok;
        if ($authority_published) {
            $failure .= "Authoritative JSON survived failed publication\n"
                if -e $output || -l $output;
        }
    }
    die $failure unless $success;
}

sub source_identity {
    my ($root, $git, $inputs) = @_;
    my $head = git_line($root, $git, 'rev-parse', 'HEAD');
    die "Malformed source HEAD bytes: " . unpack('H*', $head) . "\n"
        unless $head =~ /\A[0-9a-f]{40}\z/;
    my $status = git_capture($root, $git, 'status', '--porcelain=v1',
        '--untracked-files=no');
    my $diff = git_capture($root, $git, 'diff', '--binary', 'HEAD', '--');
    my $all = git_capture($root, $git, '-c', 'core.quotePath=false', 'status',
        '--porcelain=v1', '-z', '--untracked-files=all', '--ignored=matching');
    my $audit = length($status)
        ? { authority_inputs => [], generated_paths => [],
            generated_file_count => 0, generated_total_bytes => 0 }
        : audit_source_extras($root, $all, $inputs, $git);
    return { head => $head, tracked_clean => length($status) ? JSON::PP::false : JSON::PP::true,
        status_sha256 => sha256_hex($status), diff_sha256 => sha256_hex($diff),
        all_status_sha256 => sha256_hex($all), extras => $audit };
}

sub audit_source_extras {
    my ($root, $status, $inputs, $git) = @_;
    my %authority = map {
        File::Spec->abs2rel($inputs->{$_}{path}, $root) => 1
    } qw(gradlew gradle_wrapper_jar gradle_wrapper_properties);
    my @generated = qw(build target .gradle);
    my (@seen_authority, @seen_generated);
    my @record = split /\0/, $status, -1;
    pop @record if @record && $record[-1] eq '';
    for my $record (@record) {
        die "Malformed source status record\n" unless $record =~ /\A([?!]{2}) (.+)\z/s;
        my ($kind, $relative) = ($1, $2);
        die "Source status path is unsafe\n"
            if $relative eq '' || File::Spec->file_name_is_absolute($relative)
                || $relative =~ m{(?:\A|/)\.\.(?:/|\z)};
        if ($authority{$relative}) {
            die "Authority input is not ignored\n" unless $kind eq '!!';
            push @seen_authority, $relative;
            next;
        }
        my ($allowed_root) = grep {
            $relative eq $_ || $relative eq "$_/" || index($relative, "$_/") == 0
        } @generated;
        if ($allowed_root) {
            die "Generated output path is unexpectedly untracked rather than ignored\n"
                unless $kind eq '!!';
            push @seen_generated, $relative;
            next;
        }
        die "Unapproved untracked or ignored source path: $relative\n";
    }
    for my $relative (keys %authority) {
        next if grep { $_ eq $relative } @seen_authority;
        my $tracked = git_capture($root, $git, 'ls-files', '--', $relative);
        $tracked =~ s/\s+\z//;
        die "Authority input is neither clean tracked input nor explicitly ignored: $relative\n"
            unless $tracked eq $relative;
    }
    my ($files, $bytes) = (0, 0);
    for my $name (@generated) {
        my $path = File::Spec->catdir($root, $name);
        next unless -e $path || -l $path;
        find({ no_chdir => 1, wanted => sub {
            my $item = $File::Find::name;
            die "Generated output contains symlink: $item\n" if -l $item;
            return if -d $item;
            die "Generated output contains non-regular file: $item\n" unless -f $item;
            my @stat = stat $item;
            ++$files; $bytes += $stat[7];
            die "Generated output audit exceeds file bound\n"
                if $files > $MAX_GENERATED_FILES;
            die "Generated output audit exceeds byte bound\n"
                if $bytes > $MAX_GENERATED_BYTES;
        }}, $path);
    }
    return { authority_inputs => [sort @seen_authority],
        generated_paths => [sort @seen_generated], generated_file_count => $files,
        generated_total_bytes => $bytes };
}

sub strict_source_file {
    my ($path, $root, $label, $git) = @_;
    my $absolute = strict_regular_file($path, $MAX_INPUT, $label);
    die "$label escapes source root\n" unless path_is_inside($absolute, $root);
    git_capture($root, $git, 'ls-files', '--error-unmatch', '--',
        File::Spec->abs2rel($absolute, $root));
    return file_snapshot($absolute, $MAX_INPUT, $label);
}

sub authority_source_file {
    my ($root, $label, $required_relative) = @_;
    my $path = File::Spec->catfile($root, split m{/}, $required_relative);
    return file_snapshot(strict_regular_file($path, $MAX_INPUT, $label),
        $MAX_INPUT, $label);
}

sub executable_identity {
    my ($path, $label) = @_;
    my $absolute = strict_regular_file($path, $MAX_INPUT, $label);
    die "$label is not executable\n" unless -x $absolute;
    return file_snapshot($absolute, $MAX_INPUT, $label);
}

sub trusted_archive_tool {
    for my $path ('/usr/bin/unzip', '/bin/unzip') {
        next unless -f $path && -x $path && !-l $path;
        return executable_identity($path, 'trusted archive executable');
    }
    die "No producer-authority-selected trusted archive executable is available\n";
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
        unless canonical(source_identity($source, $tools->{git}{path}, $inputs))
            eq canonical($source_after);
    verify_identities($tools, $inputs, $producer);
    verify_snapshot($jar, $MAX_JAR, 'produced JAR');
}

sub run_bounded {
    my ($argv, $cwd, $environment, $log, $timeout, $limit, $label) = @_;
    die "$label process-tree control is unavailable on Windows\n"
        if $^O eq 'MSWin32';
    verify_output_parent();
    sysopen my $log_fh, $log, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot exclusively create $label output: $!\n";
    pipe my $reader, my $writer or die "Cannot create $label output pipe: $!\n";
    my $flags = fcntl($reader, F_GETFL, 0);
    die "Cannot inspect make pipe: $!\n" unless defined $flags;
    fcntl($reader, F_SETFL, $flags | O_NONBLOCK)
        or die "Cannot make $label output pipe nonblocking: $!\n";
    my ($interrupted, $timed_out, $truncated);
    local $SIG{$_} = sub { $interrupted = $_; $pending_signal = $_ }
        for qw(INT TERM HUP QUIT);
    my $started = time();
    my $pid = fork();
    die "Cannot fork make: $!\n" unless defined $pid;
    if ($pid == 0) {
        $SIG{$_} = 'DEFAULT' for qw(INT TERM HUP QUIT);
        close $reader; close $log_fh;
        POSIX::setpgid(0, 0) == 0 or die "Cannot create $label process group: $!\n";
        chdir $cwd or die "Cannot chdir for $label: $!\n";
        open STDOUT, '>&', $writer or die "Cannot redirect $label stdout: $!\n";
        open STDERR, '>&', STDOUT or die "Cannot redirect $label stderr: $!\n";
        close $writer;
        %ENV = %$environment;
        exec { $argv->[0] } @$argv;
        die "Cannot execute $label: $!\n";
    }
    close $writer;
    my $group_ok = eval { POSIX::setpgid($pid, $pid) == 0 } ? 1 : 0;
    my $group_deadline = time() + 0.25;
    while (!$group_ok && time() < $group_deadline) {
        $group_ok = kill 0, -$pid;
        sleep 0.005 unless $group_ok;
    }
    unless ($group_ok || kill(0, -$pid)) {
        kill 'KILL', $pid;
        waitpid($pid, 0);
        close $reader; close $log_fh;
        die "Cannot authenticate $label process group establishment\n";
    }
    my $select = IO::Select->new($reader);
    my ($status, $eof, $size, $incomplete, $leader_exit_deadline, $cleanup_deadline)
        = (undef, 0, 0, 0, undef, undef);
    my $digest = Digest::SHA->new(256);
    while (!defined($status) || !$eof) {
        for my $fh ($select->can_read(0.02)) {
            while (1) {
                my $n = sysread $fh, my $chunk, 65536;
                if (defined $n && $n) {
                    if ($size + $n > $limit) { $truncated = 1; last }
                    write_all($log_fh, $chunk, "$label output");
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
            die "waitpid failed for $label: $!\n" if $waited == -1;
            $leader_exit_deadline = time() + 0.25 if defined($status) && !$eof;
        }
        $timed_out = 1 if time() - $started >= $timeout && (!defined($status) || !$eof);
        if (defined($leader_exit_deadline) && !$eof && time() >= $leader_exit_deadline) {
            $incomplete = 1;
        }
        if ($timed_out || $truncated || $interrupted || $incomplete) {
            terminate_group($pid, $label, \$status);
            $cleanup_deadline //= time() + 1;
        }
        if (defined($cleanup_deadline) && !$eof && time() >= $cleanup_deadline) {
            $incomplete = 1; last;
        }
        if (defined($status) && $eof) {
            if (kill 0, -$pid) {
                $incomplete = 1;
                terminate_group($pid, $label, \$status);
            }
            last;
        }
    }
    terminate_group($pid, $label, \$status);
    close $reader;
    $log_fh->sync or die "Cannot sync $label output: $!\n";
    close $log_fh or die "Cannot close $label output: $!\n";
    my $finished = time();
    return { exit_code => $status >> 8, signal => $status & 127,
        timeout => $timed_out ? 1 : 0, truncated => $truncated ? 1 : 0,
        interrupted => $interrupted, incomplete => $incomplete ? 1 : 0,
        started_epoch => $started,
        finished_epoch => $finished, duration_seconds => $finished - $started,
        log_size => $size, log_sha256 => $digest->hexdigest };
}

sub validate_completion {
    my ($run) = @_;
    die "Make timed out\n" if $run->{timeout};
    die "Make log exceeded bounded size\n" if $run->{truncated};
    die "Make process tree retained or closed output after leader exit\n"
        if $run->{incomplete};
    die "Make was interrupted by $run->{interrupted}\n" if $run->{interrupted};
    die "Make terminated by signal $run->{signal}\n" if $run->{signal};
    die "Make exited with status $run->{exit_code}\n" if $run->{exit_code};
}

sub terminate_group {
    my ($pid, $label, $status_ref) = @_;
    reap_leader($pid, $status_ref);
    return unless kill 0, -$pid;
    kill 'TERM', -$pid;
    my $deadline = time() + 0.25;
    while (time() < $deadline && kill(0, -$pid)) {
        reap_leader($pid, $status_ref); sleep 0.01;
    }
    kill 'KILL', -$pid if kill 0, -$pid;
    $deadline = time() + 1;
    while (time() < $deadline && kill(0, -$pid)) {
        reap_leader($pid, $status_ref); sleep 0.01;
    }
    reap_leader($pid, $status_ref);
    die "Cannot terminate $label process group $pid\n" if kill 0, -$pid;
}

sub reap_leader {
    my ($pid, $status_ref) = @_;
    return if defined $$status_ref;
    my $waited = waitpid($pid, WNOHANG);
    $$status_ref = $? if $waited == $pid;
    die "waitpid failed while cleaning process tree: $!\n" if $waited == -1;
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
    my ($text, $expected, $label) = @_;
    my @token = $text =~ /(?<![0-9a-f])([0-9a-f]{7,40})(?![0-9a-f])/g;
    my %unique = map { $_ => 1 } @token;
    my @matching = grep { index($expected, $_) == 0 } keys %unique;
    die "$label has no unique source commit binding\n"
        unless @matching == 1;
    my @conflicting = grep { index($expected, $_) != 0 } keys %unique;
    die "$label contains conflicting commit identities\n" if @conflicting;
    return $expected;
}

sub authenticate_embedded_commit {
    my ($jar, $jar_identity, $archive_tool, $expected) = @_;
    my $raw = read_once_stable($jar, $MAX_JAR, 'JAR bytes for embedded authentication');
    die "Produced JAR mutated before embedded authentication\n"
        unless sha256_hex($raw) eq $jar_identity->{sha256}
            && length($raw) == $jar_identity->{size};
    my ($bytes, $method, $argv);
    if (substr($raw, 0, 2) eq 'PK') {
        $argv = [$archive_tool->{path}, '-p', $jar,
            'org/perlonjava/core/Configuration.class'];
        $bytes = capture_bounded($argv, 'trusted embedded JAR extraction',
            $MAX_EMBEDDED_CAPTURE);
        $method = 'trusted-unzip-configuration-class';
    } else {
        $bytes = $raw;
        $argv = [];
        $method = 'bounded-direct-content-scan';
    }
    my $commit = extract_commit($bytes, $expected, 'embedded JAR contents');
    return ($commit, {
        method => $method, argv => $argv,
        archive_tool => public_file($archive_tool),
        capture_sha256 => sha256_hex($bytes), capture_size => length($bytes),
        jar_sha256 => $jar_identity->{sha256}, resolved_commit => $commit,
    });
}

sub capture_bounded {
    my ($argv, $label, $limit) = @_;
    $limit //= $MAX_CAPTURE;
    my $path = "$output.capture-$$-" . ++$capture_sequence;
    my $run;
    my $ok = eval {
        $run = run_capture($argv, $path, 5, $limit, $label); 1
    };
    my $error = $@;
    unlink $path if -e $path || -l $path;
    die $error unless $ok;
    return $run;
}

sub run_capture {
    my ($argv, $path, $timeout, $limit, $label) = @_;
    my $environment = { LC_ALL => 'C', LANG => 'C',
        PATH => dirname($argv->[0]), HOME => $output_parent,
        TMPDIR => $output_parent, TZ => 'UTC' };
    my $run = run_bounded($argv, $output_parent, $environment, $path,
        $timeout, $limit, $label);
    die "$label timed out\n" if $run->{timeout};
    die "$label output exceeded bounded size\n" if $run->{truncated};
    die "$label process tree was incomplete\n" if $run->{incomplete};
    die "$label was interrupted\n" if $run->{interrupted};
    die "$label terminated by signal $run->{signal}\n" if $run->{signal};
    die "$label exited with status $run->{exit_code}\n" if $run->{exit_code};
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

sub validate_cli_tokens {
    my ($argv) = @_;
    my %takes_value = map { $_ => 1 } qw(source-root expected-source-commit
        expected-runner-commit expected-jar expected-jar-sha256 output perl git
        make shell java timeout mode);
    my %seen;
    for (my $i = 0; $i < @$argv; ++$i) {
        my $arg = $argv->[$i];
        die "Unknown option syntax: $arg\n" unless $arg =~ /\A--([^=]+)(?:=(.*))?\z/s;
        my ($name, $inline) = ($1, $2);
        die "Caller-supplied log or summary is forbidden\n"
            if $name =~ /\A(?:log|summary|evidence|environment|path)\z/;
        die "Unknown option --$name\n"
            unless $name eq 'help' || $takes_value{$name};
        die "Duplicate option --$name\n" if $seen{$name}++;
        if ($name eq 'help') {
            die "--help does not take a value\n" if defined $inline;
            next;
        }
        if (!defined $inline) {
            die "Option --$name requires a value\n" if $i + 1 >= @$argv;
            die "Option --$name requires a value\n" if $argv->[$i + 1] =~ /\A--/;
            ++$i;
        }
    }
}

sub assert_exact_keys {
    my ($hash, $label, @expected) = @_;
    die "$label must be an object\n" unless ref($hash) eq 'HASH';
    my $actual = join("\0", sort keys %$hash);
    my $wanted = join("\0", sort @expected);
    die "$label has an extra, duplicate, or missing schema field\n"
        unless $actual eq $wanted;
}

sub assert_descriptor {
    my ($value, $label) = @_;
    assert_exact_keys($value, $label, qw(path sha256 size));
}

sub assert_document_schema {
    my ($d) = @_;
    assert_exact_keys($d, 'document', qw(artifacts authoritative command completion
        failure_scan identity inputs kind mode producer schema schema_version seal
        source status tools verified warning_scan));
    assert_exact_keys($d->{identity}, 'identity', qw(jar_embedded_commit jar_reported_commit
        jar_sha256 runner_commit source_commit));
    assert_exact_keys($d->{source}, 'source', qw(after before root));
    for my $when (qw(before after)) {
        assert_exact_keys($d->{source}{$when}, "source.$when", qw(all_status_sha256
            diff_sha256 extras head status_sha256 tracked_clean));
        assert_exact_keys($d->{source}{$when}{extras}, "source.$when.extras",
            qw(authority_inputs generated_file_count generated_paths generated_total_bytes));
    }
    assert_exact_keys($d->{command}, 'command', qw(argv cwd duration_milliseconds
        environment finished_utc started_utc));
    assert_exact_keys($d->{tools}, 'tools', qw(git jar_tool java make perl producer shell));
    for my $name (qw(git jar_tool java make perl shell)) {
        assert_exact_keys($d->{tools}{$name}, "tools.$name",
            qw(path sha256 size version_sha256));
    }
    assert_descriptor($d->{tools}{producer}, 'tools.producer');
    assert_exact_keys($d->{inputs}, 'inputs', qw(build_gradle gradle_wrapper_jar
        gradle_wrapper_properties gradlew makefile settings_gradle));
    assert_descriptor($d->{inputs}{$_}, "inputs.$_") for keys %{$d->{inputs}};
    assert_exact_keys($d->{completion}, 'completion', qw(exit_code incomplete
        review_stop signal timeout truncated));
    for my $scan (qw(warning_scan failure_scan)) {
        assert_exact_keys($d->{$scan}, $scan, qw(classifier classifier_sha256
            complete_log_sha256 count matches));
    }
    assert_exact_keys($d->{artifacts}, 'artifacts', qw(jar jar_embedded jar_version
        make_log source_after source_before tool_versions));
    assert_descriptor($d->{artifacts}{$_}, "artifacts.$_")
        for keys %{$d->{artifacts}};
    assert_exact_keys($d->{seal}, 'seal', qw(algorithm payload_sha256));
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

sub verify_hardlink_publication {
    my ($stage, $final, $limit, $label) = @_;
    my @stage_stat = lstat $stage;
    my @final_stat = lstat $final;
    die "$label publication is missing, symlinked, or non-regular\n"
        unless @stage_stat && @final_stat && -f $stage && !-l $stage
            && -f $final && !-l $final;
    die "$label publication does not bind the staged inode\n"
        unless $stage_stat[0] == $final_stat[0]
            && $stage_stat[1] == $final_stat[1]
            && $stage_stat[7] == $final_stat[7];
    die "$label publication content differs from its stage\n"
        unless sha256_file($stage, $limit, "$label stage")
            eq sha256_file($final, $limit, "$label publication");
}

sub verify_published_descriptor {
    my ($path, $expected, $limit, $label) = @_;
    my @stat = lstat $path;
    die "$label is missing, symlinked, or non-regular\n"
        unless @stat && -f _ && !-l _;
    die "$label size changed\n" unless $stat[7] == $expected->{size};
    die "$label content changed\n"
        unless sha256_file($path, $limit, $label) eq $expected->{sha256};
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

sub publish_authority_link {
    my ($stage, $final, $published) = @_;
    verify_output_parent();
    die "Publication collision at $final\n" if -e $final || -l $final;
    authority_collision_failpoint($final);
    link $stage, $final
        or die "Cannot exclusively publish authoritative JSON $final: $!\n";
    push @$published, $final;
}

sub authority_collision_failpoint {
    my ($final) = @_;
    return unless ($ENV{REGEX_IMPLEMENTATION_MAKE_EVIDENCE_FAILPOINT} // '')
        eq 'collision-at-authority-publication';
    write_exclusive($final, "collision sentinel\n", 1024,
        'injected authority collision');
}

sub write_exclusive {
    my ($path, $bytes, $limit, $label) = @_;
    die "$label exceeds bounded size\n" if length($bytes) > $limit;
    verify_output_parent();
    sysopen my $fh, $path, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot exclusively create $label: $!\n";
    write_all($fh, $bytes, $label);
    $fh->sync or die "Cannot sync $label: $!\n";
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

sub sync_directory {
    my ($path, $label) = @_;
    my $fault = $ENV{REGEX_IMPLEMENTATION_MAKE_EVIDENCE_FAILPOINT} // '';
    die "Injected sidecar directory-sync failure\n"
        if $fault eq 'fail-sidecar-directory-sync'
            && $label eq 'output parent after sidecar publication';
    die "Injected authority directory-sync failure\n"
        if $fault eq 'fail-authority-directory-sync'
            && $label eq 'output parent after authority publication';
    open my $fh, '<', $path or die "Cannot open $label for sync: $!\n";
    $fh->sync or die "Cannot sync $label: $!\n";
    close $fh or die "Cannot close $label after sync: $!\n";
}

sub publication_failpoint {
    my ($point) = @_;
    my $fault = $ENV{REGEX_IMPLEMENTATION_MAKE_EVIDENCE_FAILPOINT} // '';
    return unless length $fault;
    if ($fault eq 'signal-after-authority-link'
            && $point eq 'after-authority-link') {
        kill 'TERM', $$ or die "Cannot inject publication signal: $!\n";
        die "Injected publication signal was not delivered\n";
    }
    die "Injected publication failure at $point\n" if $fault eq $point;
}

sub remove_private_tree {
    my ($path) = @_;
    return unless -e $path || -l $path;
    my $base = quotemeta(basename($output));
    die "Refusing unsafe stage cleanup\n"
        unless dirname($path) eq $output_parent
            && basename($path) =~ /\A\.$base\.regex_implementation-stage-[0-9-]+\z/;
    die "Refusing symlinked stage cleanup\n" if -l $path;
    my $errors = [];
    remove_tree($path, { safe => 1, error => \$errors });
    die "Cannot clean private bundle stage\n" if @$errors || -e $path || -l $path;
}

sub check_interrupted {
    die "Evidence capture interrupted by $pending_signal\n" if $pending_signal;
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
Usage: perl dev/regex/tools/run_make_evidence.pl OPTIONS
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

Acceptance publishes every retained sidecar and the seal before publishing the
authoritative JSON as the final exclusive commit marker. The producer executes
exactly the absolute make executable with no arguments in the exact source root.
The three canonical wrapper inputs are selected by fixed source-relative paths
and revalidated before and after execution. Caller log, summary, environment,
and PATH inputs are forbidden. Report mode uses a distinct .report.json output
and can never set verified or authoritative.
USAGE
    exit $status;
}
