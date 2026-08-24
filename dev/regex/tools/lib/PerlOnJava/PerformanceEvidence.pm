package PerlOnJava::PerformanceEvidence;

use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(hmac_sha256_hex sha256_hex);
use Exporter qw(import);
use File::Basename qw(dirname);
use File::Spec;
use File::Temp qw(tempfile);
use IO::Select;
use JSON::PP;
use POSIX qw(_exit WNOHANG);

our @EXPORT_OK = qw(assert_tool_authority evaluate_performance
    evidence_contract_sha256 load_json policy_sha256 seal_authority);
our $AUTHORITY_GIT;
our $AUTHORITY_GIT_SHA256;

sub evaluate_performance {
    my ($document, $requirements, $evidence_root, $trusted) = @_;
    $trusted = {} unless ref($trusted) eq 'HASH';
    local $AUTHORITY_GIT = authority_executable($trusted->{git}, 'Git');
    local $AUTHORITY_GIT_SHA256 = sha256_file($AUTHORITY_GIT);
    my (@issues, @review_stops);
    my $policy = ref($requirements->{performance_acceptance}) eq 'HASH'
        ? $requirements->{performance_acceptance} : {};
    push @issues, 'performance policy schema is missing or unsupported'
        unless ($policy->{schema_version} // '') eq '1';
    validate_ratified_policy(\@issues, $policy);
    push @issues, 'final performance evidence schema is missing or unsupported'
        unless ref($document) eq 'HASH' && ($document->{schema_version} // '') eq '1';
    push @issues, 'final performance evidence kind is wrong'
        unless ($document->{kind} // '') eq 'regex_implementation-final-performance';

    my $identity = ref($document->{identity}) eq 'HASH'
        ? $document->{identity} : {};
    validate_authority(\@issues, $document, $requirements, $identity, $trusted,
        $evidence_root);
    validate_identity(\@issues, $identity, $evidence_root, $trusted);
    my $ordinary = validate_ordinary(\@issues, $document->{ordinary}, $identity,
        $policy, $evidence_root, $trusted);
    my $psycho = validate_psycho_speed(\@issues, $document->{psycho_speed},
        $identity, $policy, $evidence_root);
    my $ordered = validate_ordered(\@issues, \@review_stops,
        $document->{ordered}, $document->{review_explanations}, $identity,
        $policy, $evidence_root, $trusted);

    my $decision = @issues ? 'failed' : @review_stops ? 'review-stop' : 'passed';
    return {
        schema_version => 1,
        decision => $decision,
        verified => $decision eq 'passed' ? JSON::PP::true : JSON::PP::false,
        policy_sha256 => policy_sha256($requirements),
        issues => \@issues,
        review_stops => \@review_stops,
        metrics => {
            ordinary => $ordinary,
            psycho_speed => $psycho,
            ordered => $ordered,
        },
    };
}

sub evidence_contract_sha256 {
    my ($document) = @_;
    return sha256_hex(canonical({ map { $_ => $document->{$_} }
        qw(schema_version kind identity ordinary psycho_speed ordered
            review_explanations) }));
}

sub seal_authority {
    my ($document, $requirements, $trusted) = @_;
    local $AUTHORITY_GIT = authority_executable($trusted->{git}, 'Git');
    local $AUTHORITY_GIT_SHA256 = sha256_file($AUTHORITY_GIT);
    my @issues;
    my $source = trusted_source_state(\@issues, $trusted);
    die join("\n", @issues) . "\n" if @issues;
    assert_tool_authority($document->{identity}, undef, $trusted);
    my $key = read_authority_key($trusted->{authority_key});
    my $authority = {
        schema_version => 1,
        kind => 'regex_implementation-performance-authority',
        complete => JSON::PP::true,
        execution_attested => JSON::PP::true,
        nonce => random_nonce($key),
        source => $source,
        authority_key_sha256 => sha256_hex($key),
        orchestrator_sha256 => sha256_file($trusted->{orchestrator}),
        ordinary_performance_producer_sha256 =>
            sha256_file($trusted->{ordinary_performance_producer}),
        performance_evaluator_sha256 => sha256_file($trusted->{performance_evaluator}),
        benchmark_sha256 => sha256_file($trusted->{benchmark}),
        perl_interpreter_sha256 => sha256_file($trusted->{perl}),
        jfr_metrics_producer_sha256 => sha256_file($trusted->{jfr_metrics_producer}),
        requirements_sha256 => sha256_file($trusted->{requirements}),
        git_executable_sha256 => sha256_file($trusted->{git}),
        ps_executable_sha256 => sha256_file($trusted->{ps}),
        uptime_executable_sha256 => sha256_file($trusted->{uptime}),
        process_tree_contract => 'unix-process-groups-v1',
        evidence_contract_sha256 => evidence_contract_sha256($document),
    };
    $authority->{hmac_sha256} = authority_hmac($authority, $key);
    return $authority;
}

sub validate_authority {
    my ($issues, $document, $requirements, $identity, $trusted, $root) = @_;
    my $authority = ref($document->{authority}) eq 'HASH'
        ? $document->{authority} : {};
    push @$issues, 'performance authority schema is missing or unsupported'
        unless ($authority->{schema_version} // '') eq '1'
            && ($authority->{kind} // '') eq 'regex_implementation-performance-authority';
    push @$issues, 'performance authority execution attestation is incomplete'
        unless true_value($authority->{complete})
            && true_value($authority->{execution_attested});
    push @$issues, 'performance authority nonce is malformed'
        unless ($authority->{nonce} // '') =~ /\A[0-9a-f]{64}\z/;

    my $key = eval { read_authority_key($trusted->{authority_key}) };
    if ($@) {
        push @$issues, "performance authority key is unusable: $@";
        return;
    }
    my $key_path = abs_path($trusted->{authority_key});
    my $absolute_root = abs_path($root);
    push @$issues, 'performance authority key must remain outside evidence root'
        if $key_path && $absolute_root && path_inside($key_path, $absolute_root);
    push @$issues, 'performance authority key identity is wrong'
        if ($authority->{authority_key_sha256} // '') ne sha256_hex($key);
    my $contract = evidence_contract_sha256($document);
    push @$issues, 'performance authority evidence contract is stale or substituted'
        if ($authority->{evidence_contract_sha256} // '') ne $contract;
    my $declared_hmac = $authority->{hmac_sha256} // '';
    push @$issues, 'performance authority HMAC is malformed'
        unless $declared_hmac =~ /\A[0-9a-f]{64}\z/;
    push @$issues, 'performance authority HMAC verification failed'
        unless secure_equal($declared_hmac, authority_hmac($authority, $key));

    my $source = trusted_source_state($issues, $trusted);
    if ($source) {
        push @$issues, 'performance authority source state is stale or substituted'
            unless canonical($authority->{source}) eq canonical($source);
        push @$issues, 'baseline source identity differs from authority-selected Git HEAD'
            if ($identity->{baseline_source_commit} // '') ne
                $source->{baseline_source_commit};
        push @$issues, 'candidate source identity differs from authority-selected Git HEAD'
            if ($identity->{candidate_source_commit} // '') ne
                $source->{candidate_source_commit};
        push @$issues, 'candidate parent identity differs from actual Git parent'
            if ($identity->{candidate_parent_commit} // '') ne
                $source->{candidate_parent_commit};
        push @$issues, 'latest Perl identity differs from frozen checkout Git HEAD'
            if ($identity->{perl5_commit} // '') ne $source->{perl5_commit};
    }

    for my $spec (
        [orchestrator_sha256 => $trusted->{orchestrator}, 'checked-in orchestrator'],
        [ordinary_performance_producer_sha256 =>
            $trusted->{ordinary_performance_producer},
            'checked-in ordinary performance producer'],
        [performance_evaluator_sha256 => $trusted->{performance_evaluator},
            'checked-in performance evaluator'],
        [benchmark_sha256 => $trusted->{benchmark}, 'checked-in benchmark'],
        [perl_interpreter_sha256 => $trusted->{perl},
            'authority-selected Perl interpreter'],
        [jfr_metrics_producer_sha256 => $trusted->{jfr_metrics_producer},
            'checked-in JFR metrics producer'],
        [requirements_sha256 => $trusted->{requirements}, 'checked-in requirements']) {
        my ($field, $path, $label) = @$spec;
        my $maximum = $field eq 'requirements_sha256' ? 4 * 1024 * 1024
            : $field eq 'perl_interpreter_sha256' ? undef : 1024 * 1024;
        my $trusted_path = trusted_file($issues, $path, $label, $maximum,
            $field eq 'perl_interpreter_sha256');
        push @$issues, "performance authority $field is wrong"
            if $trusted_path && ($authority->{$field} // '') ne sha256_file($trusted_path);
    }
    for my $spec ([git_executable_sha256 => $trusted->{git}, 'Git executable'],
            [ps_executable_sha256 => $trusted->{ps}, 'ps executable'],
            [uptime_executable_sha256 => $trusted->{uptime}, 'uptime executable']) {
        my ($field, $path, $label) = @$spec;
        my $selected = trusted_file($issues, $path, "authority-selected $label",
            64 * 1024 * 1024, 1);
        push @$issues, "performance authority $field is wrong"
            if $selected && ($authority->{$field} // '') ne sha256_file($selected);
    }
    for my $spec ([git_executable => 'git_executable_sha256'],
            [ps_executable => 'ps_executable_sha256'],
            [uptime_executable => 'uptime_executable_sha256']) {
        my ($identity_field, $authority_field) = @$spec;
        push @$issues, "sealed $identity_field differs from authority-selected executable"
            if (($identity->{$identity_field} // {})->{sha256} // '') ne
                ($authority->{$authority_field} // '');
    }
    push @$issues, 'performance authority process-tree contract is unsupported'
        unless ($authority->{process_tree_contract} // '') eq
            'unix-process-groups-v1' && $^O ne 'MSWin32';
    push @$issues, 'evidence benchmark differs from trusted current-source benchmark'
        if (($identity->{benchmark} // {})->{sha256} // '') ne
            ($authority->{benchmark_sha256} // '');
    push @$issues, 'evidence ordinary producer differs from trusted current-source producer'
        if (($identity->{ordinary_performance_producer} // {})->{sha256} // '') ne
            ($authority->{ordinary_performance_producer_sha256} // '');
    push @$issues, 'evidence evaluator differs from trusted current-source evaluator'
        if (($identity->{performance_evaluator} // {})->{sha256} // '') ne
            ($authority->{performance_evaluator_sha256} // '');
    push @$issues, 'evidence Perl interpreter differs from authority-selected Perl'
        if (($identity->{perl_interpreter} // {})->{sha256} // '') ne
            ($authority->{perl_interpreter_sha256} // '');
    push @$issues, 'evidence JFR helper differs from trusted current-source producer'
        if (($identity->{jfr_metrics_producer} // {})->{sha256} // '') ne
            ($authority->{jfr_metrics_producer_sha256} // '');

    validate_candidate_test_sources($issues, $document, $trusted->{candidate_source});
    if (defined($trusted->{candidate_source}) && -d $trusted->{candidate_source}) {
        for my $spec ([orchestrator => 'run_final_performance.pl'],
                [ordinary_performance_producer =>
                    'run_regex_performance.pl'],
                [performance_evaluator => File::Spec->catfile('lib',
                    'PerlOnJava', 'PerformanceEvidence.pm')],
                [benchmark => 'regex_benchmark.pl'],
                [jfr_metrics_producer => 'JfrMetrics.java']) {
            my ($field, $name) = @$spec;
            my $candidate_path = File::Spec->catfile($trusted->{candidate_source},
                'dev', 'regex', 'tools', $name);
            push @$issues, "$field differs from authority-selected candidate source"
                unless -f $candidate_path && -f $trusted->{$field}
                    && sha256_file($candidate_path) eq sha256_file($trusted->{$field});
        }
    }
}

sub trusted_source_state {
    my ($issues, $trusted) = @_;
    my %state;
    for my $spec ([baseline => 'baseline_source'], [candidate => 'candidate_source'],
            [perl5 => 'perl5_source']) {
        my ($label, $field) = @$spec;
        my $path = $trusted->{$field};
        if (!defined($path) || !-d $path) {
            push @$issues, "authority-selected $label source checkout is missing";
            next;
        }
        my $head = git_line($issues, $path, "$label source", qw(rev-parse HEAD));
        my $dirty = git_output($issues, $path, "$label source",
            qw(status --porcelain --untracked-files=all));
        push @$issues, "authority-selected $label source checkout is not clean"
            if defined($dirty) && length($dirty);
        $state{"${label}_source_commit"} = $head if defined $head;
    }
    return if grep { !defined($_) } @state{qw(baseline_source_commit
        candidate_source_commit perl5_source_commit)};
    my $parents = git_output($issues, $trusted->{candidate_source},
        'candidate source', qw(rev-list --parents -n 1 HEAD));
    my $parent;
    if (defined $parents) {
        $parents =~ s/\s+\z//;
        my @field = split / /, $parents;
        if (@field != 2 || $field[0] ne $state{candidate_source_commit}
                || $field[1] !~ /\A[0-9a-f]{40}\z/) {
            push @$issues, 'authority-selected candidate must have exactly one parent';
        } else {
            $parent = $field[1];
        }
    }
    $state{candidate_parent_commit} = $parent if defined $parent;
    push @$issues, 'authority-selected candidate is not the direct child of baseline'
        if defined($parent) && $parent ne $state{baseline_source_commit};
    $state{perl5_commit} = delete $state{perl5_source_commit};
    return \%state;
}

sub validate_candidate_test_sources {
    my ($issues, $document, $candidate_source) = @_;
    return unless defined($candidate_source) && -d $candidate_source;
    my $rows = (($document->{psycho_speed} // {})->{rows});
    return unless ref($rows) eq 'ARRAY';
    for my $row (@$rows) {
        next unless ref($row) eq 'HASH';
        my $test = $row->{test} // '';
        next unless $test =~ /\Are\/(?:pat_psycho|pat_psycho_thr|speed|speed_thr)\.t\z/;
        my $path = File::Spec->catfile($candidate_source, 'perl5_t', 't',
            File::Spec->splitdir($test));
        if (!-f $path) {
            push @$issues, "$test is missing from authority-selected candidate source";
            next;
        }
        push @$issues, "$test evidence source differs from authority-selected candidate source"
            if (($row->{test_source} // {})->{sha256} // '') ne sha256_file($path);
    }
}

sub authority_hmac {
    my ($authority, $key) = @_;
    my %unsigned = %$authority;
    delete $unsigned{hmac_sha256};
    return hmac_sha256_hex(canonical(\%unsigned), $key);
}

sub read_authority_key {
    my ($path) = @_;
    die "authority key path is missing\n" unless defined($path) && -f $path;
    die "authority key must remain outside the evidence tree\n"
        if -s $path < 32 || -s $path > 4096;
    if ($^O eq 'MSWin32') {
        die "Windows authority-key ACL validation is unsupported; A232 requires a private fixed-location ACL contract\n";
    }
    my $mode = (stat $path)[2] & 0777;
    die sprintf("authority key must have exact mode 0600 (found %04o)\n", $mode)
        unless $mode == 0600;
    return read_raw($path);
}

sub random_nonce {
    my ($key) = @_;
    return hmac_sha256_hex(join(':', time(), $$, rand(), {}), $key);
}

sub secure_equal {
    my ($left, $right) = @_;
    return 0 unless defined($left) && defined($right)
        && length($left) == length($right);
    my $difference = 0;
    $difference |= ord(substr($left, $_, 1)) ^ ord(substr($right, $_, 1))
        for 0 .. length($left) - 1;
    return $difference == 0;
}

sub git_line {
    my ($issues, $directory, $label, @args) = @_;
    my $output = git_output($issues, $directory, $label, @args);
    return unless defined $output;
    $output =~ s/\s+\z//;
    if ($output =~ /\n/ || $output !~ /\A[0-9a-f]{40}\z/) {
        push @$issues, "$label Git identity is malformed";
        return;
    }
    return $output;
}

sub git_output {
    my ($issues, $directory, $label, @args) = @_;
    if (!defined($AUTHORITY_GIT)) {
        push @$issues, "authority-selected Git is missing for $label";
        return;
    }
    local %ENV = (closed_checker_environment(), GIT_CONFIG_NOSYSTEM => '1',
        GIT_CONFIG_GLOBAL => File::Spec->devnull());
    if (sha256_file($AUTHORITY_GIT) ne $AUTHORITY_GIT_SHA256) {
        push @$issues, "authority-selected Git identity changed for $label";
        return;
    }
    open my $fh, '-|', $AUTHORITY_GIT, '--no-pager',
        '-c', 'core.fsmonitor=false', '-c', 'core.hooksPath=/dev/null',
        '-C', $directory, @args or do {
        push @$issues, "cannot execute Git for $label";
        return;
    };
    my $output = do { local $/; <$fh> };
    if (!close $fh) {
        push @$issues, "Git command failed for $label";
        return;
    }
    if (sha256_file($AUTHORITY_GIT) ne $AUTHORITY_GIT_SHA256) {
        push @$issues, "authority-selected Git identity changed for $label";
        return;
    }
    return $output;
}

sub validate_identity {
    my ($issues, $identity, $root, $trusted) = @_;
    for my $field (qw(baseline_source_commit candidate_source_commit
            candidate_parent_commit perl5_commit)) {
        push @$issues, "$field is missing or not a full Git SHA"
            unless ($identity->{$field} // '') =~ /\A[0-9a-f]{40}\z/;
    }
    push @$issues, 'candidate parent is not the exact performance baseline'
        if ($identity->{candidate_parent_commit} // '') ne
            ($identity->{baseline_source_commit} // '');
    for my $field (qw(benchmark jfc jdk_executable jdk_version_log
            ordinary_performance_producer performance_evaluator perl_interpreter
            execution_environment
            baseline_jar candidate_jar baseline_launcher candidate_launcher
            interpreter_launcher jfr_tool jfr_metrics_producer time_executable
            git_executable ps_executable uptime_executable
            ordered_test_source ordered_fixture_manifest
            ordered_fixture_tree_manifest dbix_archive)) {
        validate_artifact($issues, $identity->{$field}, $root, "identity $field");
    }
    push @$issues, 'evidence-supplied JFR replay launcher is forbidden'
        if exists $identity->{jfr_replay_launcher};
    my $trusted_java = trusted_file($issues, $trusted->{java},
        'authority-selected JDK executable', undef, 1);
    my $trusted_helper = trusted_file($issues, $trusted->{jfr_metrics_producer},
        'checked-in JFR metrics producer', 1024 * 1024, 0);
    push @$issues, 'JDK executable identity differs from authority-selected --java'
        if $trusted_java && (($identity->{jdk_executable} // {})->{sha256} // '')
            ne sha256_file($trusted_java);
    push @$issues, 'JFR metrics producer identity differs from checked-in helper'
        if $trusted_helper
            && (($identity->{jfr_metrics_producer} // {})->{sha256} // '')
                ne sha256_file($trusted_helper);
    my $environment_path = validate_artifact($issues,
        $identity->{execution_environment}, $root,
        'identity execution_environment', 1024 * 1024);
    validate_execution_environment_contract($issues, $environment_path,
        $identity, $trusted)
        if $environment_path;
}

sub validate_execution_environment_contract {
    my ($issues, $path, $identity, $trusted) = @_;
    my $contract = eval { load_json($path, 'execution environment contract') };
    if ($@ || ref($contract) ne 'HASH') {
        push @$issues, 'execution environment contract is invalid';
        return;
    }
    push @$issues, 'execution environment contract is incomplete'
        unless ($contract->{schema_version} // '') eq '1'
            && true_value($contract->{complete});
    push @$issues, 'execution environment inheritance allowlist is not empty'
        unless canonical($contract->{inheritance_allowlist}) eq canonical([]);
    my %forbidden = map { $_ => 1 }
        @{ref($contract->{forbidden_ambient}) eq 'ARRAY'
            ? $contract->{forbidden_ambient} : []};
    for my $field (qw(JPERL_OPTS JPERL_UNIMPLEMENTED JAVA_TOOL_OPTIONS
            _JAVA_OPTIONS JDK_JAVA_OPTIONS JAVA_HOME CLASSPATH PERL5OPT PERL5LIB
            PERL5DB PERLIO PERL_UNICODE PERL_HASH_SEED PERL_PERTURB_KEYS
            GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_COMMON_DIR
            GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES
            GIT_CONFIG GIT_CONFIG_GLOBAL GIT_CONFIG_SYSTEM GIT_CONFIG_COUNT
            GIT_EXEC_PATH GIT_CEILING_DIRECTORIES GIT_NAMESPACE GIT_SSH
            GIT_SSH_COMMAND GIT_ASKPASS GIT_PAGER LD_PRELOAD
            DYLD_INSERT_LIBRARIES BASH_ENV ENV CDPATH IFS)) {
        push @$issues, "execution environment contract does not reject $field"
            unless $forbidden{$field};
    }
    my $base = ref($contract->{base_effective_environment}) eq 'HASH'
        ? $contract->{base_effective_environment} : {};
    push @$issues, 'execution environment base locale/timezone is not deterministic'
        unless ($base->{LANG} // '') eq 'C' && ($base->{LC_ALL} // '') eq 'C'
            && ($base->{TZ} // '') eq 'UTC';
    for my $field (qw(HOME PERLONJAVA_HOME TMPDIR PERLONJAVA_JAVA_BIN)) {
        push @$issues, "execution environment base omits $field"
            unless defined($base->{$field}) && !ref($base->{$field})
                && length($base->{$field});
    }
    push @$issues, 'execution environment PATH must be closed'
        unless exists($base->{PATH}) && ($base->{PATH} // 'x') eq '';
    push @$issues, 'execution environment process-tree contract is unsupported'
        unless ($contract->{process_tree_contract} // '') eq
            'unix-process-groups-v1' && $^O ne 'MSWin32';
    my $tools = ref($contract->{authority_executables}) eq 'HASH'
        ? $contract->{authority_executables} : {};
    for my $spec ([git_sha256 => 'git_executable'],
            [ps_sha256 => 'ps_executable'], [uptime_sha256 => 'uptime_executable'],
            [java_sha256 => 'jdk_executable'], [perl_sha256 => 'perl_interpreter']) {
        push @$issues, "execution environment $spec->[0] identity is wrong"
            if ($tools->{$spec->[0]} // '') ne
                (($identity->{$spec->[1]} // {})->{sha256} // '');
    }
    my $selected_java = abs_path($base->{PERLONJAVA_JAVA_BIN} // '');
    my $trusted_java = abs_path($trusted->{java} // '');
    push @$issues, 'execution environment Java path is not authority-selected'
        unless $selected_java && $trusted_java && $selected_java eq $trusted_java;
    push @$issues, 'execution environment Java identity is wrong'
        unless $selected_java && -f $selected_java
            && sha256_file($selected_java) eq
                (($identity->{jdk_executable} // {})->{sha256} // '');
}

sub validate_ordinary {
    my ($issues, $node, $identity, $policy, $root) = @_;
    if (ref($node) ne 'HASH') {
        push @$issues, 'ordinary performance evidence is missing';
        return {};
    }
    my $path = validate_artifact($issues, $node->{artifact}, $root,
        'ordinary performance artifact', 8 * 1024 * 1024);
    return {} unless $path;
    my $ordinary = eval { load_json($path, 'ordinary performance evidence') };
    if (!$ordinary) {
        push @$issues, "ordinary performance artifact is invalid: $@";
        return {};
    }
    push @$issues, 'ordinary performance producer did not verify its evidence'
        unless true_value($ordinary->{verified});
    push @$issues, 'ordinary performance kind is wrong'
        unless ($ordinary->{kind} // '') eq 'performance';
    my $minimum = $policy->{minimum_ordinary_samples};
    if (!bounded_integer($minimum) || $minimum < 5 || $minimum > 100) {
        push @$issues, 'ordinary sample policy is missing or outside its bounded range';
        $minimum = 5;
    }
    my $baseline = $ordinary->{baseline_seconds};
    my $candidate = $ordinary->{candidate_seconds};
    for my $pair (['baseline', $baseline], ['candidate', $candidate]) {
        push @$issues, "$pair->[0] ordinary samples are incomplete"
            unless numeric_array($pair->[1], $minimum);
    }
    my @expected_order = map { qw(baseline candidate) } 1 .. $minimum;
    push @$issues, 'ordinary performance sample order is wrong'
        unless true_value($ordinary->{alternating_order})
            && canonical($ordinary->{execution_order}) eq canonical(\@expected_order);
    push @$issues, 'ordinary semantic checksum is wrong'
        if ($ordinary->{semantic_checksum} // '') ne
            ($policy->{ordinary_semantic_checksum} // '');
    my $source = ref($ordinary->{source}) eq 'HASH' ? $ordinary->{source} : {};
    push @$issues, 'ordinary baseline source identity is wrong'
        if (($source->{baseline} // {})->{commit} // '') ne
            ($identity->{baseline_source_commit} // '');
    push @$issues, 'ordinary candidate source identity is wrong'
        if (($source->{candidate} // {})->{commit} // '') ne
            ($identity->{candidate_source_commit} // '');
    push @$issues, 'ordinary candidate parent identity is wrong'
        if (($source->{candidate} // {})->{parent_commit} // '') ne
            ($identity->{baseline_source_commit} // '');
    my $artifacts = ref($ordinary->{artifacts}) eq 'HASH'
        ? $ordinary->{artifacts} : {};
    for my $mapping (
        [benchmark => 'benchmark'], [baseline_jar => 'baseline_jar'],
        [candidate_jar => 'candidate_jar'],
        [baseline_launcher => 'baseline_launcher'],
        [candidate_launcher => 'candidate_launcher']) {
        push @$issues, "ordinary $mapping->[0] identity is wrong"
            if (($artifacts->{$mapping->[0]} // {})->{sha256} // '') ne
                (($identity->{$mapping->[1]} // {})->{sha256} // '');
    }
    push @$issues, 'ordinary Java identity is wrong'
        if (($artifacts->{java} // {})->{sha256} // '') ne
            (($identity->{jdk_executable} // {})->{sha256} // '');
    for my $side (qw(baseline candidate)) {
        my $logs = (($artifacts->{raw_logs} // {})->{$side});
        if (ref($logs) ne 'ARRAY' || @$logs != $minimum + 2) {
            push @$issues, "ordinary $side raw logs are incomplete";
            next;
        }
        my @paths = map {
            validate_artifact($issues, $_, $root, "ordinary $side raw log",
                1024 * 1024)
        } @$logs;
        next if grep { !defined($_) } @paths;
        my $version = read_raw($paths[0]);
        my %reported = map { $_ => 1 } ($version =~ /\b([0-9a-f]{7,40})\b/g);
        push @$issues, "ordinary $side identity log does not bind its source commit"
            unless keys(%reported) == 1
                && index($identity->{"${side}_source_commit"}, (keys %reported)[0]) == 0;
        my @raw = map {
            parse_ordinary_metric($issues, "ordinary $side raw sample", read_raw($_),
                $identity->{"${side}_source_commit"},
                ($identity->{"${side}_jar"} // {})->{sha256},
                $policy->{ordinary_operations})
        } @paths[1 .. $#paths];
        next if grep { !defined($_) } @raw;
        my @measured = map { 0 + $_->{elapsed_seconds} } @raw[1 .. $#raw];
        push @$issues, "ordinary $side declared timing summary differs from raw logs"
            unless canonical(\@measured) eq canonical($side eq 'baseline'
                ? $baseline : $candidate);
        push @$issues, "ordinary $side declared throughput differs from raw logs"
            unless canonical([map { 0 + $_->{throughput} } @raw[1 .. $#raw]])
                eq canonical($ordinary->{"${side}_throughput"});
        push @$issues, "ordinary $side raw checksum differs from semantic checksum"
            if grep { $_->{checksum} ne ($ordinary->{semantic_checksum} // '') } @raw;
    }
    if (numeric_array($baseline, $minimum) && numeric_array($candidate, $minimum)) {
        push @$issues, 'candidate ordinary median wall time regressed'
            if median($candidate) > median($baseline);
    }
    return {
        baseline_median_seconds => numeric_array($baseline, $minimum)
            ? median($baseline) : undef,
        candidate_median_seconds => numeric_array($candidate, $minimum)
            ? median($candidate) : undef,
        sample_count_per_side => $minimum,
    };
}

sub parse_ordinary_metric {
    my ($issues, $label, $text, $source_commit, $jar_sha256, $operations) = @_;
    my @lines = grep { /^REGEX_IMPLEMENTATION_REGEX_PERFORMANCE\b/ } split /\r?\n/, $text;
    if (@lines != 1) {
        push @$issues, "$label is missing or duplicated";
        return;
    }
    my %value;
    my @tokens = split /\s+/, $lines[0];
    shift @tokens;
    for my $token (@tokens) {
        if ($token =~ /\A(elapsed_seconds|throughput|checksum|jar_sha256|source_commit)=([^=\s]+)\z/
                && !exists $value{$1}) {
            $value{$1} = $2;
        } else {
            push @$issues, "$label contains a malformed or duplicate metric token";
            return;
        }
    }
    unless (positive_number($value{elapsed_seconds})
            && positive_number($value{throughput})) {
        push @$issues, "$label elapsed or throughput metric is malformed";
        return;
    }
    push @$issues, "$label elapsed or throughput exceeds its bounded range"
        if $value{elapsed_seconds} > 10_000_000
            || $value{throughput} > 1_000_000_000_000;
    push @$issues, "$label source identity is wrong"
        if ($value{source_commit} // '') ne $source_commit;
    push @$issues, "$label JAR identity is wrong"
        if ($value{jar_sha256} // '') ne $jar_sha256;
    push @$issues, "$label checksum is malformed"
        unless ($value{checksum} // '') =~ /\A[0-9a-f]{64}\z/;
    if (number($operations) && $operations > 0) {
        my $expected = $operations / $value{elapsed_seconds};
        my $allowance = $expected * 0.000_001;
        $allowance = 0.01 if $allowance < 0.01;
        push @$issues, "$label throughput is inconsistent with elapsed time"
            if abs($value{throughput} - $expected) > $allowance;
    } else {
        push @$issues, 'ordinary operation count policy is missing';
    }
    return \%value;
}

sub validate_psycho_speed {
    my ($issues, $node, $identity, $policy, $root) = @_;
    my $rows = ref($node) eq 'HASH' ? $node->{rows} : undef;
    if (ref($rows) ne 'ARRAY') {
        push @$issues, 'psycho/speed rows are missing';
        return {};
    }
    my %expected;
    for my $spec (@{$policy->{psycho_speed_rows} // []}) {
        $expected{"$_|$spec->{test}"} = $spec for qw(jvm interpreter);
    }
    my %seen;
    my (%tap_by_key, %source_by_key);
    for my $row (@$rows) {
        if (ref($row) ne 'HASH') {
            push @$issues, 'psycho/speed row is not an object';
            next;
        }
        my $key = ($row->{backend} // '') . '|' . ($row->{test} // '');
        my $spec = $expected{$key};
        if (!$spec || $seen{$key}++) {
            push @$issues, "unexpected or duplicate psycho/speed row: $key";
            next;
        }
        push @$issues, "$key source identity is wrong"
            if ($row->{source_commit} // '') ne
                ($identity->{candidate_source_commit} // '');
        push @$issues, "$key JAR identity is wrong"
            if ($row->{jar_sha256} // '') ne
                (($identity->{candidate_jar} // {})->{sha256} // '');
        my $launcher_field = $row->{backend} eq 'jvm'
            ? 'candidate_launcher' : 'interpreter_launcher';
        if ($launcher_field eq 'interpreter_launcher') {
            validate_artifact($issues, $identity->{$launcher_field}, $root,
                'identity interpreter_launcher');
        }
        push @$issues, "$key launcher identity is wrong"
            if ($row->{launcher_sha256} // '') ne
                (($identity->{$launcher_field} // {})->{sha256} // '');
        push @$issues, "$key exit status is nonzero or malformed"
            unless bounded_integer($row->{exit_code}) && "$row->{exit_code}" eq '0';
        push @$issues, "$key timed out" if true_value($row->{timeout});
        push @$issues, "$key is truncated" if true_value($row->{truncated});
        my $tap = validate_artifact($issues, $row->{tap}, $root, "$key TAP",
            16 * 1024 * 1024);
        my $command_path = validate_artifact($issues, $row->{command}, $root,
            "$key command", 256 * 1024);
        validate_psycho_command($issues, $key, $command_path, $row, $identity)
            if $command_path;
        validate_artifact($issues, $row->{test_source}, $root, "$key test source");
        $source_by_key{$key} = ($row->{test_source} // {})->{sha256} // '';
        next unless $tap;
        my $tap_text = read_raw($tap);
        $tap_by_key{$key} = normalize_tap($tap_text);
        my $parsed = parse_tap($tap_text);
        my $expected_plan = bounded_integer($spec->{plan})
            && $spec->{plan} <= 1_000_000 ? 0 + $spec->{plan} : -2;
        my $expected_passed = bounded_integer($spec->{passed})
            && $spec->{passed} <= 1_000_000 ? 0 + $spec->{passed} : -2;
        my $expected_skipped = bounded_integer($spec->{skipped})
            && $spec->{skipped} <= 1_000_000 ? 0 + $spec->{skipped} : -2;
        push @$issues, "$key TAP plan is wrong"
            unless $parsed->{plan} == $expected_plan;
        push @$issues, "$key TAP pass count is wrong"
            unless $parsed->{passed} == $expected_passed;
        push @$issues, "$key TAP ok count is wrong"
            unless $parsed->{ok_count} == $expected_plan;
        push @$issues, "$key TAP skip count is wrong"
            unless $parsed->{skipped} == $expected_skipped;
        push @$issues, "$key TAP contains failures" if $parsed->{failed};
        push @$issues, "$key TAP contains a bailout" if $parsed->{bailout};
        push @$issues, "$key TAP contains unexpected output" if $parsed->{unexpected};
    }
    push @$issues, 'psycho/speed row set is incomplete'
        unless canonical([sort keys %seen]) eq canonical([sort keys %expected]);
    for my $spec (@{$policy->{psycho_speed_rows} // []}) {
        my $test = $spec->{test};
        next unless exists $tap_by_key{"jvm|$test"}
            && exists $tap_by_key{"interpreter|$test"};
        push @$issues, "$test JVM/interpreter TAP differs"
            if $tap_by_key{"jvm|$test"} ne $tap_by_key{"interpreter|$test"};
        push @$issues, "$test JVM/interpreter source identity differs"
            if $source_by_key{"jvm|$test"} ne $source_by_key{"interpreter|$test"};
    }
    return { rows => scalar(@$rows), expected_rows => scalar(keys %expected) };
}

sub validate_psycho_command {
    my ($issues, $key, $path, $row, $identity) = @_;
    my $command = eval { load_json($path, "$key command") };
    if ($@ || ref($command) ne 'HASH') {
        push @$issues, "$key command envelope is invalid";
        return;
    }
    push @$issues, "$key command is not authority-selected"
        unless ($command->{schema_version} // '') eq '1'
            && true_value($command->{authority_selected});
    my $argv = $command->{argv};
    push @$issues, "$key command argv is malformed"
        unless ref($argv) eq 'ARRAY' && @$argv == 2
            && !grep { !defined($_) || ref($_) || length($_) > 4096 } @$argv;
    push @$issues, "$key command timeout is missing or exceeds 600 seconds"
        unless bounded_integer($command->{timeout_seconds})
            && $command->{timeout_seconds} > 0
            && $command->{timeout_seconds} <= 600;
    my $launcher_field = $row->{backend} eq 'jvm'
        ? 'candidate_launcher' : 'interpreter_launcher';
    for my $spec (
        [source_commit => $identity->{candidate_source_commit}],
        [jar_sha256 => ($identity->{candidate_jar} // {})->{sha256}],
        [launcher_sha256 => ($identity->{$launcher_field} // {})->{sha256}],
        [test_source_sha256 => ($row->{test_source} // {})->{sha256}]) {
        push @$issues, "$key command $spec->[0] identity is wrong"
            if ($command->{$spec->[0]} // '') ne ($spec->[1] // '');
    }
    push @$issues, "$key command environment contract identity is wrong"
        if ($command->{environment_contract_sha256} // '') ne
            (($identity->{execution_environment} // {})->{sha256} // '');
}

sub validate_ordered {
    my ($issues, $review, $node, $explanations, $identity, $policy, $root,
        $trusted) = @_;
    my $runs = ref($node) eq 'HASH' ? $node->{runs} : undef;
    if (ref($runs) ne 'ARRAY') {
        push @$issues, '87ordered run evidence is missing';
        return {};
    }
    my @expected_order = @{$policy->{ordered_execution_order} // []};
    push @$issues, '87ordered execution order is wrong'
        unless canonical([map { ref($_) eq 'HASH' ? ($_->{side} // '') : '' } @$runs])
            eq canonical(\@expected_order);
    my %metrics = (baseline => [], candidate => []);
    my @admissions;
    for my $index (0 .. $#$runs) {
        my $run = $runs->[$index];
        if (ref($run) ne 'HASH') {
            push @$issues, "87ordered run $index is not an object";
            next;
        }
        my $side = $run->{side} // '';
        my $prefix = "87ordered run $index $side";
        push @$issues, "$prefix has wrong source identity"
            if ($run->{source_commit} // '') ne
                ($identity->{"${side}_source_commit"} // '');
        for my $field (qw(jar launcher)) {
            push @$issues, "$prefix has wrong $field identity"
                if ($run->{"${field}_sha256"} // '') ne
                    (($identity->{"${side}_${field}"} // {})->{sha256} // '');
        }
        for my $field (qw(jdk_executable jdk_version_log jfc)) {
            push @$issues, "$prefix has wrong $field identity"
                if ($run->{"${field}_sha256"} // '') ne
                    (($identity->{$field} // {})->{sha256} // '');
        }
        push @$issues, "$prefix exit status is nonzero or malformed"
            unless bounded_integer($run->{exit_code}) && "$run->{exit_code}" eq '0';
        push @$issues, "$prefix timed out" if true_value($run->{timeout});
        push @$issues, "$prefix timeout bound is missing or exceeds 900 seconds"
            unless bounded_integer($run->{timeout_seconds})
                && $run->{timeout_seconds} > 0 && $run->{timeout_seconds} <= 900;
        my $command_path = validate_artifact($issues, $run->{command}, $root,
            "$prefix command", 256 * 1024);
        validate_command($issues, $prefix, $command_path, $run, $identity)
            if $command_path;
        my $environment_path = validate_artifact($issues, $run->{environment},
            $root, "$prefix environment", 1024 * 1024);
        validate_environment($issues, $prefix, $environment_path, $identity)
            if $environment_path;
        my %operating_artifact;
        for my $field (qw(process_inventory_before process_inventory_after
                load_before load_after)) {
            $operating_artifact{$field} = validate_artifact($issues,
                $run->{$field}, $root, "$prefix $field", 16 * 1024 * 1024);
        }
        my $admission_path = validate_artifact($issues, $run->{load_admission},
            $root, "$prefix load admission", 1024 * 1024);
        $admissions[$index] = validate_admission($issues, $prefix,
            $admission_path, $run, $policy) if $admission_path;
        my $tap_path = validate_artifact($issues, $run->{tap}, $root,
            "$prefix TAP", 64 * 1024 * 1024);
        if ($tap_path) {
            my $text = read_raw($tap_path);
            my $tap = parse_tap($text);
            push @$issues, "$prefix TAP is not 1271/1271"
                unless $tap->{plan} == 1271 && $tap->{ok_count} == 1271
                    && $tap->{passed} == 1271
                    && !$tap->{failed} && !$tap->{bailout};
            push @$issues, "$prefix TAP contains unexpected output"
                if $tap->{unexpected};
            push @$issues, "$prefix leak audit is missing or duplicated"
                unless scalar(() = $text =~ /Auto checked 5 references for leaks - none detected/g) == 1;
        }
        my $time_path = validate_artifact($issues, $run->{time_raw}, $root,
            "$prefix raw time output", 64 * 1024);
        my $time = $time_path ? parse_time($issues, $prefix, read_raw($time_path)) : {};
        push @$issues, 'JFR recording-size policy is missing or malformed'
            unless bounded_integer($policy->{maximum_jfr_recording_bytes});
        push @$issues, 'JFR metrics-size policy is missing or malformed'
            unless bounded_integer($policy->{maximum_jfr_metrics_bytes});
        my $maximum_jfr = bounded_integer($policy->{maximum_jfr_recording_bytes})
            ? $policy->{maximum_jfr_recording_bytes} : 2 * 1024 * 1024 * 1024;
        my $maximum_metrics = bounded_integer($policy->{maximum_jfr_metrics_bytes})
            ? $policy->{maximum_jfr_metrics_bytes} : 1024 * 1024;
        my $jfr_path = validate_artifact($issues, $run->{jfr_recording}, $root,
            "$prefix JFR recording", $maximum_jfr);
        my $metrics_path = validate_artifact($issues, $run->{jfr_metrics}, $root,
            "$prefix sealed JFR metrics", $maximum_metrics);
        my $summary_path = validate_artifact($issues, $run->{jfr_summary}, $root,
            "$prefix JFR summary", 16 * 1024 * 1024);
        my $jfr = {};
        if ($metrics_path && $jfr_path && $command_path) {
            my $export = eval { load_json($metrics_path, "$prefix sealed JFR metrics") };
            if ($@) {
                push @$issues, "$prefix sealed JFR metrics are invalid: $@";
            } else {
                $jfr = parse_sealed_jfr_metrics($issues, $prefix, $export,
                    $run, $identity, $jfr_path, $command_path);
                replay_sealed_jfr_metrics($issues, $prefix, $metrics_path,
                    $run, $identity, $root, $jfr_path, $command_path, $trusted);
            }
        }
        if ($summary_path) {
            my $summary = read_raw($summary_path);
            push @$issues, "$prefix JFR summary does not prove zero DataLoss"
                unless $summary =~ /^\s*jdk\.DataLoss\s+0\b/m;
        }
        if ($side =~ /\A(?:baseline|candidate)\z/ && %$time && %$jfr) {
            push @{$metrics{$side}}, { %$time, %$jfr };
        }
    }
    for my $side (qw(baseline candidate)) {
        push @$issues, "$side 87ordered sample count is not two"
            unless @{$metrics{$side}} == 2;
    }
    for my $pair ([0, 1], [2, 3]) {
        next unless $admissions[$pair->[0]] && $admissions[$pair->[1]];
        push @$issues, "87ordered pair $pair->[0]/$pair->[1] competing owner set changed"
            if $admissions[$pair->[0]]->{owner_set_sha256} ne
                $admissions[$pair->[1]]->{owner_set_sha256};
    }
    my %aggregate;
    if (@{$metrics{baseline}} == 2 && @{$metrics{candidate}} == 2) {
        for my $field (qw(wall_seconds user_seconds total_allocation_bytes
                root_reflective_allocation_bytes final_live_heap_bytes
                peak_committed_heap_bytes max_rss_bytes nmt_committed_bytes
                nmt_reserved_bytes young_gc_count old_gc_count
                total_gc_pause_nanos max_gc_pause_nanos)) {
            $aggregate{"baseline_$field"} = median([map { $_->{$field} } @{$metrics{baseline}}]);
            $aggregate{"candidate_$field"} = median([map { $_->{$field} } @{$metrics{candidate}}]);
        }
        for my $side (qw(baseline candidate)) {
            $aggregate{"${side}_allocation_bytes_per_second"} =
                $aggregate{"${side}_total_allocation_bytes"} /
                    $aggregate{"${side}_wall_seconds"};
            $aggregate{"${side}_root_reflective_bytes_per_second"} =
                $aggregate{"${side}_root_reflective_allocation_bytes"} /
                    $aggregate{"${side}_wall_seconds"};
        }
        push @$issues, 'candidate 87ordered median wall time regressed'
            if $aggregate{candidate_wall_seconds} > $aggregate{baseline_wall_seconds};
        push @$issues, 'candidate 87ordered median user CPU regressed'
            if $aggregate{candidate_user_seconds} > $aggregate{baseline_user_seconds};
        push @$issues, 'root-wrapper plus reflective-field allocation did not improve by 20%'
            if $aggregate{candidate_root_reflective_allocation_bytes} >
                $aggregate{baseline_root_reflective_allocation_bytes} *
                    0.80;
        push @$issues, 'total sampled allocation regressed'
            if $aggregate{candidate_total_allocation_bytes} >
                $aggregate{baseline_total_allocation_bytes};
        my $live_allowance = $aggregate{baseline_final_live_heap_bytes} * 0.05;
        my $absolute = 16 * 1024 * 1024;
        $live_allowance = $absolute if $absolute > $live_allowance;
        push @$issues, 'final post-old-GC live heap exceeds its allowance'
            if $aggregate{candidate_final_live_heap_bytes} >
                $aggregate{baseline_final_live_heap_bytes} + $live_allowance;
        my $review_ratio = 1.10;
        for my $field (qw(peak_committed_heap_bytes max_rss_bytes)) {
            next unless $aggregate{"candidate_$field"} >
                $aggregate{"baseline_$field"} * $review_ratio;
            my $explanation = find_explanation($explanations, $field, $issues, $root);
            push @$review, {
                metric => $field,
                baseline => $aggregate{"baseline_$field"},
                candidate => $aggregate{"candidate_$field"},
                explanation_sealed => $explanation ? JSON::PP::true : JSON::PP::false,
            };
            push @$issues, "$field review-stop explanation is missing"
                unless $explanation;
        }
    }
    return { %aggregate, execution_order => \@expected_order };
}

sub validate_admission {
    my ($issues, $prefix, $path, $run, $policy) = @_;
    my $admission = eval { load_json($path, "$prefix load admission") };
    if ($@ || ref($admission) ne 'HASH') {
        push @$issues, "$prefix load admission is invalid";
        return;
    }
    push @$issues, "$prefix load admission schema is unsupported"
        unless ($admission->{schema_version} // '') eq '1';
    push @$issues, "$prefix load admission is incomplete"
        unless true_value($admission->{complete});
    for my $field (qw(process_inventory_before process_inventory_after
            load_before load_after)) {
        push @$issues, "$prefix load admission $field identity is wrong"
            if ($admission->{"${field}_sha256"} // '') ne
                (($run->{$field} // {})->{sha256} // '');
    }
    for my $field (qw(load_average_before load_average_after)) {
        push @$issues, "$prefix load admission $field is malformed"
            unless number($admission->{$field}) && $admission->{$field} >= 0;
    }
    for my $field (qw(started_at finished_at)) {
        push @$issues, "$prefix load admission $field is not an immutable UTC timestamp"
            unless ($admission->{$field} // '') =~
                /\A\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ\z/;
    }
    my $before = $admission->{active_expensive_owners_before};
    my $after = $admission->{active_expensive_owners_after};
    my $valid = 1;
    for my $set ([before => $before], [after => $after]) {
        if (ref($set->[1]) ne 'ARRAY'
                || grep { !defined($_) || ref($_) || $_ !~ /\A[A-Za-z0-9_.:-]+\z/ }
                    @{$set->[1] // []}) {
            push @$issues, "$prefix load admission $set->[0] owner set is malformed";
            $valid = 0;
            next;
        }
        my %unique = map { $_ => 1 } @{$set->[1]};
        push @$issues, "$prefix load admission $set->[0] owner set has duplicates"
            if keys(%unique) != @{$set->[1]};
        push @$issues, 'expensive-owner policy is missing or malformed'
            unless bounded_integer($policy->{maximum_expensive_owners});
        my $owner_limit = bounded_integer($policy->{maximum_expensive_owners})
            ? $policy->{maximum_expensive_owners} : 3;
        push @$issues, "$prefix load admission exceeds the expensive-owner limit"
            if @{$set->[1]} > $owner_limit;
        push @$issues, "$prefix load admission omits the performance lane"
            unless $unique{'regex_implementation-performance'};
    }
    push @$issues, "$prefix competing owner set changed during the sample"
        if $valid && canonical([sort @$before]) ne canonical([sort @$after]);
    my $unexpected = $admission->{unexpected_perlonjava_jvms};
    push @$issues, "$prefix load admission found an unexpected PerlOnJava JVM"
        unless ref($unexpected) eq 'ARRAY' && !@$unexpected;
    return unless $valid;
    return { owner_set_sha256 => sha256_hex(canonical([sort @$before])) };
}

sub parse_sealed_jfr_metrics {
    my ($issues, $prefix, $summary, $run, $identity, $jfr_path, $command_path) = @_;
    if (ref($summary) ne 'HASH' || ($summary->{schema_version} // '') ne '1') {
        push @$issues, "$prefix sealed JFR metrics schema is unsupported";
        return {};
    }
    push @$issues, "$prefix sealed JFR metrics are incomplete"
        unless true_value($summary->{complete}) && !true_value($summary->{truncated});
    my $bound = ref($summary->{identity}) eq 'HASH' ? $summary->{identity} : {};
    my %expected = (
        jfr_recording_sha256 => $run->{jfr_recording}{sha256},
        command_sha256 => $run->{command}{sha256},
        jfr_tool_sha256 => ($identity->{jfr_tool} // {})->{sha256},
        producer_sha256 => ($identity->{jfr_metrics_producer} // {})->{sha256},
        jdk_executable_sha256 => ($identity->{jdk_executable} // {})->{sha256},
        jdk_version_log_sha256 => ($identity->{jdk_version_log} // {})->{sha256},
        jfc_sha256 => ($identity->{jfc} // {})->{sha256},
    );
    for my $field (sort keys %expected) {
        push @$issues, "$prefix sealed JFR metrics $field identity is wrong"
            if ($bound->{$field} // '') ne ($expected{$field} // '');
    }
    my $metrics = ref($summary->{metrics}) eq 'HASH' ? $summary->{metrics} : {};
    for my $field (qw(final_live_heap_bytes peak_committed_heap_bytes
            total_allocation_bytes root_reflective_allocation_bytes
            nmt_committed_bytes nmt_reserved_bytes data_loss_events
            young_gc_count old_gc_count total_gc_pause_nanos max_gc_pause_nanos)) {
        push @$issues, "$prefix sealed JFR metric $field is missing, non-integral, or out of range"
            unless bounded_integer($metrics->{$field});
    }
    push @$issues, "$prefix sealed JFR metrics report data loss"
        if number($metrics->{data_loss_events}) && $metrics->{data_loss_events} != 0;
    push @$issues, "$prefix sealed JFR metrics lack a post-old-GC observation"
        unless true_value($summary->{post_old_gc_observed});
    push @$issues, "$prefix sealed JFR metrics have incomplete GC pairing"
        unless true_value($summary->{gc_pairing_complete});
    push @$issues, "$prefix sealed JFR metrics report unsupported NMT"
        unless ($summary->{nmt_status} // '') eq 'supported';
    return {} if grep { !bounded_integer($metrics->{$_}) }
        qw(final_live_heap_bytes peak_committed_heap_bytes total_allocation_bytes
            root_reflective_allocation_bytes nmt_committed_bytes
            nmt_reserved_bytes data_loss_events young_gc_count old_gc_count
            total_gc_pause_nanos max_gc_pause_nanos);
    return { map { $_ => 0 + $metrics->{$_} }
        qw(final_live_heap_bytes peak_committed_heap_bytes total_allocation_bytes
            root_reflective_allocation_bytes nmt_committed_bytes
            nmt_reserved_bytes data_loss_events young_gc_count old_gc_count
            total_gc_pause_nanos max_gc_pause_nanos) };
}

sub replay_sealed_jfr_metrics {
    my ($issues, $prefix, $sealed_path, $run, $identity, $root, $jfr_path,
        $command_path, $trusted) = @_;
    my $java_identity = validate_artifact($issues, $identity->{jdk_executable}, $root,
        "$prefix replay JDK executable identity");
    my $helper_identity = validate_artifact($issues, $identity->{jfr_metrics_producer},
        $root, "$prefix replay JFR metrics producer", 1024 * 1024);
    my $jfr_tool = validate_artifact($issues, $identity->{jfr_tool}, $root,
        "$prefix replay JFR tool");
    my $jdk_version = validate_artifact($issues, $identity->{jdk_version_log},
        $root, "$prefix replay JDK version log", 1024 * 1024);
    my $jfc = validate_artifact($issues, $identity->{jfc}, $root,
        "$prefix replay JFC", 16 * 1024 * 1024);
    my $java = trusted_file($issues, $trusted->{java},
        "$prefix authority-selected JDK executable", undef, 1);
    my $helper = trusted_file($issues, $trusted->{jfr_metrics_producer},
        "$prefix checked-in JFR metrics producer", 1024 * 1024, 0);
    return unless $java_identity && $helper_identity && $java && $helper
        && $jfr_tool && $jdk_version && $jfc;
    return if sha256_file($java) ne sha256_file($java_identity)
        || sha256_file($helper) ne sha256_file($helper_identity);

    my $java_hash_before = sha256_file($java);
    my @command = ($java, $helper_identity,
        '--recording', $jfr_path,
        '--command', $command_path,
        '--jfr-tool', $jfr_tool,
        '--jdk-executable', $java_identity,
        '--jdk-version-log', $jdk_version,
        '--jfc', $jfc,
        '--helper', $helper_identity);
    my ($out_fh, $out_path) = tempfile('.regex_implementation-jfr-replay-out-XXXXXX',
        DIR => $root, UNLINK => 1);
    chmod 0600, $out_path;
    pipe my $reader, my $writer or do {
        push @$issues, "$prefix bounded JFR replay could not create output pipe: $!";
        return;
    };
    my $pid = fork();
    if (!defined $pid) {
        push @$issues, "$prefix bounded JFR replay could not fork: $!";
        return;
    }
    if ($pid == 0) {
        close $reader;
        eval { POSIX::setpgid(0, 0) };
        open STDOUT, '>&', $writer or _exit(126);
        open STDERR, '>&', STDOUT or _exit(126);
        close $writer;
        close $out_fh;
        %ENV = closed_checker_environment($java);
        if (!exec { $command[0] } @command) {
            _exit(127);
        }
    }
    close $writer;
    eval { POSIX::setpgid($pid, $pid) };
    my $group = eval { getpgrp($pid) };
    if (!defined($group) || $group != $pid || $^O eq 'MSWin32') {
        kill 'TERM', $pid;
        select undef, undef, undef, 0.2;
        kill 'KILL', $pid;
        waitpid($pid, 0);
        push @$issues, "$prefix bounded JFR replay lacks the Unix process-group contract";
        return;
    }
    my $selector = IO::Select->new($reader);
    my ($written, $status, $leader_reaped, $eof, $timed_out, $oversized) =
        (0, undef, 0, 0, 0, 0);
    my $deadline = time() + 120;
    my $ok = eval {
        while (!$leader_reaped || !$eof) {
            for my $ready ($selector->can_read(0.02)) {
                my $count = sysread($ready, my $chunk, 64 * 1024);
                die "$prefix bounded JFR replay output read failed: $!\n"
                    unless defined $count;
                if ($count == 0) {
                    $selector->remove($ready);
                    $eof = 1;
                } elsif ($written + $count > 1024 * 1024) {
                    $oversized = 1;
                    last;
                } else {
                    print {$out_fh} $chunk
                        or die "Cannot write bounded JFR replay output: $!\n";
                    $written += $count;
                }
            }
            last if $oversized;
            my $waited = $leader_reaped ? 0 : waitpid($pid, WNOHANG);
            if ($waited == $pid) {
                $status = $?;
                $leader_reaped = 1;
            }
            die "$prefix bounded JFR replay waitpid failed: $!\n"
                if $waited == -1;
            if (time() >= $deadline) {
                $timed_out = 1;
                last;
            }
        }
        1;
    };
    my $error = $@;
    terminate_group($pid, $leader_reaped);
    close $reader if defined fileno($reader);
    if (!close($out_fh) && $ok) {
        $ok = 0;
        $error = "$prefix bounded JFR replay output close failed: $!\n";
    }
    if (!$ok) {
        $error =~ s/\s+\z//;
        push @$issues, $error;
        return;
    }
    if (!-f $java || sha256_file($java) ne $java_hash_before) {
        push @$issues, "$prefix authority-selected JDK identity changed during replay";
        return;
    }
    if ($timed_out) {
        push @$issues, "$prefix bounded JFR replay timed out";
        return;
    }
    if ($oversized) {
        push @$issues, "$prefix bounded JFR replay output exceeded 1 MiB";
        return;
    }
    if ($status != 0) {
        my $error = -s $out_path && -s $out_path <= 64 * 1024
            ? read_raw($out_path) : 'bounded combined output unavailable';
        $error =~ s/\s+\z//;
        push @$issues, "$prefix bounded JFR replay failed: $error";
        return;
    }
    if (!-f $out_path || !-s $out_path || -s $out_path > 1024 * 1024) {
        push @$issues, "$prefix bounded JFR replay output is empty or oversized";
        return;
    }
    my $actual = eval { load_json($out_path, "$prefix bounded JFR replay output") };
    if ($@) {
        push @$issues, "$prefix bounded JFR replay output is invalid: $@";
        return;
    }
    my $sealed = eval { load_json($sealed_path, "$prefix sealed JFR metrics") };
    if ($@ || canonical($actual) ne canonical($sealed)) {
        push @$issues, "$prefix sealed JFR metrics differ from bounded raw-JFR replay";
    }
}

sub validate_command {
    my ($issues, $prefix, $path, $run, $identity) = @_;
    my $command = eval { load_json($path, "$prefix command") };
    if ($@ || ref($command) ne 'HASH') {
        push @$issues, "$prefix command envelope is invalid";
        return;
    }
    push @$issues, "$prefix command schema is unsupported"
        unless ($command->{schema_version} // '') eq '1';
    push @$issues, "$prefix command is not authority-selected"
        unless true_value($command->{authority_selected});
    my $argv = $command->{argv};
    push @$issues, "$prefix command argv is missing or unbounded"
        unless ref($argv) eq 'ARRAY' && @$argv >= 2 && @$argv <= 64
            && !grep { !defined($_) || ref($_) || length($_) > 4096 } @$argv;
    push @$issues, "$prefix command timeout identity is wrong"
        unless bounded_integer($command->{timeout_seconds})
            && bounded_integer($run->{timeout_seconds})
            && "$command->{timeout_seconds}" eq "$run->{timeout_seconds}";
    for my $field (qw(source_commit jar_sha256 launcher_sha256
            jdk_executable_sha256 jdk_version_log_sha256 jfc_sha256
            jfr_tool_sha256 jfr_metrics_producer_sha256 time_executable_sha256
            ordered_test_source_sha256 ordered_fixture_manifest_sha256
            ordered_fixture_tree_manifest_sha256
            dbix_archive_sha256 environment_sha256 perl5_commit)) {
        my $expected = $field eq 'jfr_tool_sha256'
            ? (($identity->{jfr_tool} // {})->{sha256} // '')
            : $field eq 'jfr_metrics_producer_sha256'
            ? (($identity->{jfr_metrics_producer} // {})->{sha256} // '')
            : $field eq 'time_executable_sha256'
            ? (($identity->{time_executable} // {})->{sha256} // '')
            : $field eq 'ordered_test_source_sha256'
            ? (($identity->{ordered_test_source} // {})->{sha256} // '')
            : $field eq 'ordered_fixture_manifest_sha256'
            ? (($identity->{ordered_fixture_manifest} // {})->{sha256} // '')
            : $field eq 'ordered_fixture_tree_manifest_sha256'
            ? (($identity->{ordered_fixture_tree_manifest} // {})->{sha256} // '')
            : $field eq 'dbix_archive_sha256'
            ? (($identity->{dbix_archive} // {})->{sha256} // '')
            : $field eq 'environment_sha256'
            ? (($run->{environment} // {})->{sha256} // '')
            : $field eq 'perl5_commit'
            ? ($identity->{perl5_commit} // '')
            : ($run->{$field} // '');
        push @$issues, "$prefix command $field identity is wrong"
            if ($command->{$field} // '') ne $expected;
    }
    push @$issues, "$prefix command JFR size bound is missing or exceeds 2 GiB"
        unless number($command->{jfr_max_bytes})
            && $command->{jfr_max_bytes} > 0
            && $command->{jfr_max_bytes} <= 2 * 1024 * 1024 * 1024;
    push @$issues, "$prefix command does not execute t/87ordered.t"
        unless ref($argv) eq 'ARRAY' && @$argv == 6
            && ($argv->[1] // '') =~ /\A(?:-lp|-v)\z/
            && ($argv->[2] // '') eq '-o'
            && ($argv->[3] // '') eq '/dev/fd/3'
            && ($argv->[5] // '') eq 't/87ordered.t';
    if (ref($argv) eq 'ARRAY' && @$argv == 6) {
        for my $spec ([0 => 'time_executable'], [4 => "$run->{side}_launcher"]) {
            my ($index, $field) = @$spec;
            my $path = abs_path($argv->[$index] // '');
            push @$issues, "$prefix command argv executable identity is wrong"
                unless $path && -f $path
                    && sha256_file($path) eq
                        (($identity->{$field} // {})->{sha256} // '');
        }
    }
    my $unset = $command->{unset_environment};
    my $valid_unset = ref($unset) eq 'ARRAY'
        && !grep { !defined($_) || ref($_) || $_ !~ /\A[A-Za-z_][A-Za-z0-9_]*\z/ }
            @{$unset // []};
    push @$issues, "$prefix command unset environment list is malformed"
        unless $valid_unset;
    my %unset = $valid_unset ? map { $_ => 1 } @$unset : ();
    push @$issues, "$prefix command does not explicitly unset temporary regex policy"
        unless $unset{JPERL_UNIMPLEMENTED};
    for my $field (qw(JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS
            JAVA_HOME CLASSPATH PERL5OPT PERL5LIB JPERL_OPTS)) {
        push @$issues, "$prefix command does not clear $field"
            unless $unset{$field};
    }
}

sub validate_environment {
    my ($issues, $prefix, $path, $identity) = @_;
    my $environment = eval { load_json($path, "$prefix environment") };
    if ($@ || ref($environment) ne 'HASH') {
        push @$issues, "$prefix environment envelope is invalid";
        return;
    }
    push @$issues, "$prefix environment schema is unsupported"
        unless ($environment->{schema_version} // '') eq '1';
    push @$issues, "$prefix environment envelope is incomplete"
        unless true_value($environment->{complete});
    push @$issues, "$prefix base environment identity is wrong"
        if ($environment->{base_environment_sha256} // '') ne
            (($identity->{execution_environment} // {})->{sha256} // '');
    my $unset_list = $environment->{unset};
    my $valid_unset = ref($unset_list) eq 'ARRAY'
        && !grep { !defined($_) || ref($_) || $_ !~ /\A[A-Za-z_][A-Za-z0-9_]*\z/ }
            @{$unset_list // []};
    push @$issues, "$prefix environment unset list is malformed"
        unless $valid_unset;
    my %unset = $valid_unset ? map { $_ => 1 } @$unset_list : ();
    push @$issues, "$prefix environment does not unset JPERL_UNIMPLEMENTED"
        unless $unset{JPERL_UNIMPLEMENTED};
    for my $field (qw(JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS
            JAVA_HOME CLASSPATH PERL5OPT PERL5LIB JPERL_OPTS)) {
        push @$issues, "$prefix environment does not clear $field"
            unless $unset{$field};
    }
    my $effective = ref($environment->{effective_environment}) eq 'HASH'
        ? $environment->{effective_environment} : {};
    for my $field (qw(JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS
            JAVA_HOME CLASSPATH PERL5OPT PERL5LIB)) {
        push @$issues, "$prefix effective environment leaks $field"
            if exists $effective->{$field};
    }
    for my $field (keys %$effective) {
        push @$issues, "$prefix effective environment leaks injection variable $field"
            if $field =~ /\A(?:GIT|PERL|JAVA|JDK|CLASSPATH|JPERL|REGEX_IMPLEMENTATION)(?:_|\z)/
                && $field !~ /\A(?:PERLONJAVA_JAR|PERLONJAVA_HOME|PERLONJAVA_JAVA_BIN|JPERL_OPTS)\z/;
    }
    for my $field (qw(PATH LANG LC_ALL TZ HOME PERLONJAVA_HOME TMPDIR
            PERLONJAVA_JAR PERLONJAVA_JAVA_BIN JPERL_OPTS)) {
        push @$issues, "$prefix effective environment omits $field"
            unless defined($effective->{$field}) && !ref($effective->{$field});
    }
    my $effective_java = abs_path($effective->{PERLONJAVA_JAVA_BIN} // '');
    push @$issues, "$prefix effective Java path/identity is not authority-selected"
        unless $effective_java && -f $effective_java
            && sha256_file($effective_java) eq
                (($identity->{jdk_executable} // {})->{sha256} // '');
    my $roots = ref($environment->{private_roots}) eq 'HASH'
        ? $environment->{private_roots} : {};
    for my $field (qw(HOME PERLONJAVA_HOME TMPDIR)) {
        my $value = $roots->{$field};
        push @$issues, "$prefix environment $field private root is missing"
            unless defined($value) && !ref($value)
                && File::Spec->file_name_is_absolute($value);
    }
}

sub parse_time {
    my ($issues, $prefix, $text) = @_;
    if ($text =~ /^REGEX_IMPLEMENTATION_TIME\b/m) {
        push @$issues, "$prefix hand-authored normalized timing summary is forbidden";
        return {};
    }
    my %value;
    my @mac_real = ($text =~ /^\s*([0-9.]+)\s+real\s*$/mg);
    my @mac_user = ($text =~ /^\s*([0-9.]+)\s+user\s*$/mg);
    my @mac_sys = ($text =~ /^\s*([0-9.]+)\s+sys\s*$/mg);
    my @mac_rss = ($text =~ /^\s*(\d+)\s+maximum resident set size\s*$/mg);
    if (@mac_real == 1 && @mac_user == 1 && @mac_sys == 1 && @mac_rss == 1) {
        @value{qw(wall_seconds user_seconds system_seconds max_rss_bytes)} =
            ($mac_real[0], $mac_user[0], $mac_sys[0], $mac_rss[0]);
    } else {
        my @gnu_user = ($text =~ /^\s*User time \(seconds\):\s*([0-9.]+)\s*$/mg);
        my @gnu_sys = ($text =~ /^\s*System time \(seconds\):\s*([0-9.]+)\s*$/mg);
        my @gnu_wall = ($text =~ /^\s*Elapsed \(wall clock\) time \([^)]*\):\s*([^\s]+)\s*$/mg);
        my @gnu_rss = ($text =~ /^\s*Maximum resident set size \(kbytes\):\s*(\d+)\s*$/mg);
        if (@gnu_user == 1 && @gnu_sys == 1 && @gnu_wall == 1 && @gnu_rss == 1) {
            $value{wall_seconds} = elapsed_seconds($gnu_wall[0]);
            $value{user_seconds} = $gnu_user[0];
            $value{system_seconds} = $gnu_sys[0];
            if (!number($gnu_rss[0])
                    || $gnu_rss[0] > int(8_000_000_000_000_000 / 1024)) {
                push @$issues, "$prefix GNU maximum RSS overflows its bounded range";
                return {};
            }
            $value{max_rss_bytes} = $gnu_rss[0] * 1024;
        } else {
            push @$issues, "$prefix raw /usr/bin/time output is unsupported, missing, or duplicated";
            return {};
        }
    }
    for my $field (qw(wall_seconds user_seconds system_seconds max_rss_bytes)) {
        push @$issues, "$prefix time metric $field is missing or malformed"
            unless number($value{$field}) && $value{$field} >= 0;
    }
    push @$issues, "$prefix wall/user/system time exceeds its bounded range"
        if grep { number($value{$_}) && $value{$_} > 10_000_000 }
            qw(wall_seconds user_seconds system_seconds);
    push @$issues, "$prefix maximum RSS exceeds its bounded range"
        if number($value{max_rss_bytes})
            && $value{max_rss_bytes} > 8_000_000_000_000_000;
    return {} if grep { !number($value{$_}) || $value{$_} < 0 }
        qw(wall_seconds user_seconds system_seconds max_rss_bytes);
    if ($value{wall_seconds} <= 0 || $value{user_seconds} <= 0
            || $value{max_rss_bytes} <= 0) {
        push @$issues, "$prefix time metrics must have positive wall, user, and RSS values";
        return {};
    }
    return { map { $_ => 0 + $value{$_} }
        qw(wall_seconds user_seconds system_seconds max_rss_bytes) };
}

sub elapsed_seconds {
    my ($value) = @_;
    return $value if number($value);
    my @part = split /:/, $value;
    return -1 unless @part == 2 || @part == 3;
    return -1 if grep { !number($_) } @part;
    return -1 if @part == 2 && ($part[0] > 10_000_000 || $part[1] >= 60);
    return -1 if @part == 3
        && ($part[0] > 200_000 || $part[1] >= 60 || $part[2] >= 60);
    return @part == 2 ? $part[0] * 60 + $part[1]
        : $part[0] * 3600 + $part[1] * 60 + $part[2];
}

sub find_explanation {
    my ($explanations, $metric, $issues, $root) = @_;
    return unless ref($explanations) eq 'ARRAY';
    my @match = grep { ref($_) eq 'HASH' && ($_->{metric} // '') eq $metric }
        @$explanations;
    return unless @match == 1;
    return validate_artifact($issues, $match[0]{artifact}, $root,
        "$metric review-stop explanation");
}

sub validate_artifact {
    my ($issues, $artifact, $root, $label, $maximum_size) = @_;
    if (ref($artifact) ne 'HASH') {
        push @$issues, "$label descriptor is missing";
        return;
    }
    my $path = $artifact->{path} // '';
    my $sha = $artifact->{sha256} // '';
    if (!$path || $sha !~ /\A[0-9a-f]{64}\z/) {
        push @$issues, "$label descriptor is malformed";
        return;
    }
    my $resolved = File::Spec->file_name_is_absolute($path)
        ? $path : File::Spec->catfile($root, File::Spec->splitdir($path));
    my $absolute = abs_path($resolved);
    my $absolute_root = abs_path($root);
    if (!$absolute || !$absolute_root || !path_inside($absolute, $absolute_root)) {
        push @$issues, "$label is missing or escapes the evidence root";
        return;
    }
    if (!-f $absolute || !-s $absolute) {
        push @$issues, "$label is missing or empty";
        return;
    }
    if (defined($maximum_size) && -s $absolute > $maximum_size) {
        push @$issues, "$label exceeds its bounded size";
        return;
    }
    if (sha256_file($absolute) ne $sha) {
        push @$issues, "$label hash mismatch";
        return;
    }
    if (defined($artifact->{size}) && (!bounded_integer($artifact->{size})
            || $artifact->{size} > 8_000_000_000_000_000
            || "$artifact->{size}" ne "" . (-s $absolute))) {
        push @$issues, "$label size mismatch";
        return;
    }
    return $absolute;
}

sub trusted_file {
    my ($issues, $path, $label, $maximum_size, $executable) = @_;
    if (!defined($path) || !-f $path || ($executable && !-x $path)) {
        push @$issues, "$label is missing or unusable";
        return;
    }
    if (defined($maximum_size) && -s $path > $maximum_size) {
        push @$issues, "$label exceeds its bounded size";
        return;
    }
    return abs_path($path);
}

sub parse_tap {
    my ($text) = @_;
    my @plans = ($text =~ /^1\.\.(\d+)\s*$/mg);
    my @ok = ($text =~ /^ok\s+\d+\b([^\n]*)/mg);
    my @not_ok = ($text =~ /^not ok\s+\d+\b/mg);
    my $skipped = grep { /#\s*skip\b/i } @ok;
    my $plan = @plans == 1 && bounded_integer($plans[0])
        && $plans[0] <= 1_000_000 ? 0 + $plans[0] : -1;
    return {
        plan => $plan,
        ok_count => scalar(@ok), passed => scalar(@ok) - $skipped,
        failed => scalar(@not_ok), skipped => $skipped,
        bailout => $text =~ /^Bail out!/mi ? 1 : 0,
        unexpected => scalar(grep {
            length($_) && $_ !~ /\A(?:TAP version\s+\d+|1\.\.\d+|ok\s+\d+\b|not ok\s+\d+\b|#|Auto checked 5 references for leaks - none detected)/
        } split /\r?\n/, $text),
    };
}

sub normalize_tap {
    my ($text) = @_;
    $text =~ s/\r\n?/\n/g;
    $text =~ s/[ \t]+$//mg;
    $text =~ s/\s+\z/\n/;
    return $text;
}

sub policy_sha256 {
    my ($requirements) = @_;
    return sha256_hex(canonical($requirements->{performance_acceptance} // {}));
}

sub load_json {
    my ($file, $label, $maximum_size) = @_;
    die "$label $file exceeds its bounded size\n"
        if defined($maximum_size) && -s $file > $maximum_size;
    open my $fh, '<:raw', $file or die "Cannot read $label $file: $!\n";
    my $json = do { local $/; <$fh> };
    close $fh or die "Cannot close $label $file: $!\n";
    my $value = eval { JSON::PP->new->utf8->decode($json) };
    die "Cannot parse $label $file: $@\n" if $@;
    return $value;
}

sub read_raw {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "Cannot read $file: $!\n";
    my $value = do { local $/; <$fh> };
    close $fh or die "Cannot close $file: $!\n";
    return $value;
}

sub sha256_file {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "Cannot read $file: $!\n";
    my $sha = Digest::SHA->new(256)->addfile($fh)->hexdigest;
    close $fh or die "Cannot close $file: $!\n";
    return $sha;
}

sub path_inside {
    my ($path, $root) = @_;
    my $relative = File::Spec->abs2rel($path, $root);
    return $relative ne File::Spec->updir
        && $relative !~ m{^\.\.(?:[\\/]|\z)};
}

sub numeric_array {
    my ($value, $minimum) = @_;
    return ref($value) eq 'ARRAY' && @$value >= $minimum
        && !grep { !number($_) || $_ <= 0 } @$value;
}

sub validate_ratified_policy {
    my ($issues, $policy) = @_;
    my %contract = (
        process_tree_contract => 'unix-process-groups-v1',
        windows_process_tree_policy =>
            'fail-closed-until-a232-native-tree-validation',
        authority_key_unix_mode => '0600',
        authority_key_windows_policy =>
            'fail-closed-private-fixed-location-acl-until-a232-validation',
    );
    for my $field (sort keys %contract) {
        push @$issues, "ratified performance contract $field is missing or changed"
            unless defined($policy->{$field}) && !ref($policy->{$field})
                && $policy->{$field} eq $contract{$field};
    }
    my $thresholds = ref($policy->{thresholds}) eq 'HASH'
        ? $policy->{thresholds} : {};
    my %exact = (
        root_reflective_allocation_reduction => '0.2',
        live_heap_relative_allowance => '0.05',
        live_heap_absolute_allowance_bytes => '16777216',
        committed_heap_rss_review_ratio => '0.1',
    );
    for my $field (sort keys %exact) {
        my $value = $thresholds->{$field};
        push @$issues, "ratified performance threshold $field is missing, malformed, or changed"
            unless defined($value) && !ref($value)
                && canonical_decimal($value) eq $exact{$field};
    }
    for my $spec ([minimum_ordinary_samples => 5, 100],
            [ordinary_operations => 1, 1_000_000_000],
            [maximum_jfr_recording_bytes => 1, 2 * 1024 * 1024 * 1024],
            [maximum_jfr_metrics_bytes => 1, 16 * 1024 * 1024],
            [maximum_expensive_owners => 1, 100]) {
        my ($field, $minimum, $maximum) = @$spec;
        my $value = $policy->{$field};
        push @$issues, "ratified performance policy $field is malformed or out of range"
            unless bounded_integer($value) && $value >= $minimum
                && $value <= $maximum;
    }
    my $rows = $policy->{psycho_speed_rows};
    if (ref($rows) ne 'ARRAY' || @$rows != 4) {
        push @$issues, 'ratified psycho/speed policy rows are malformed';
    } else {
        for my $row (@$rows) {
            if (ref($row) ne 'HASH') {
                push @$issues, 'ratified psycho/speed policy row is malformed';
                next;
            }
            for my $field (qw(plan passed skipped)) {
                push @$issues, "ratified psycho/speed $field is malformed or out of range"
                    unless bounded_integer($row->{$field})
                        && $row->{$field} <= 1_000_000;
            }
        }
    }
}

sub bounded_integer {
    my ($value) = @_;
    return number($value) && "$value" =~ /\A\d+\z/;
}

sub median {
    my ($values) = @_;
    my @sorted = sort { $a <=> $b } @$values;
    my $middle = int(@sorted / 2);
    return @sorted % 2 ? $sorted[$middle]
        : $sorted[$middle - 1]
            + ($sorted[$middle] - $sorted[$middle - 1]) / 2;
}

sub number {
    my ($value) = @_;
    return 0 unless defined($value) && !ref($value);
    my $text = "$value";
    return 0 if length($text) > 24;
    return 0 unless $text =~ /\A(?:\d{1,16}(?:\.\d{1,9})?|\.\d{1,9})\z/;
    if ($text !~ /\./) {
        (my $normalized = $text) =~ s/\A0+(?=\d)//;
        return 0 if length($normalized) > 16;
        return 0 if length($normalized) == 16
            && $normalized gt '8000000000000000';
    } else {
        my ($whole, $fraction) = $text =~ /\A(\d*)\.(\d+)\z/;
        (my $digits = "$whole$fraction") =~ s/\A0+//;
        return 0 if length($digits) > 15;
    }
    my $numeric = 0 + $text;
    return 0 if $numeric != $numeric || abs($numeric) > 8_000_000_000_000_000;
    return 1;
}

sub canonical_decimal {
    my ($value) = @_;
    return '' unless number($value);
    my $text = "$value";
    if ($text =~ /\./) {
        $text =~ s/0+\z//;
        $text =~ s/\.\z//;
    }
    $text =~ s/\A0+(?=\d)//;
    return $text;
}

sub authority_executable {
    my ($path, $label) = @_;
    die "authority-selected $label executable is missing\n"
        unless defined($path) && -f $path && -x $path;
    my $absolute = abs_path($path)
        or die "cannot resolve authority-selected $label executable\n";
    return $absolute;
}

sub assert_tool_authority {
    my ($identity, $root, $selected, $expected) = @_;
    die "tool authority identity is malformed\n" unless ref($identity) eq 'HASH';
    $selected = {} unless ref($selected) eq 'HASH';
    $expected = {} unless ref($expected) eq 'HASH';
    for my $spec ([git_executable => 'git'], [ps_executable => 'ps'],
            [uptime_executable => 'uptime']) {
        my ($identity_field, $selected_field) = @$spec;
        my $artifact = $identity->{$identity_field};
        die "sealed $identity_field identity is malformed\n"
            unless ref($artifact) eq 'HASH'
                && ($artifact->{sha256} // '') =~ /\A[0-9a-f]{64}\z/;
        my $path = authority_executable($selected->{$selected_field},
            $selected_field);
        my $current = sha256_file($path);
        my $initial = $expected->{$identity_field};
        die "authority-selected $selected_field executable changed before publication\n"
            if defined($initial) && $current ne $initial;
        die "sealed $identity_field differs from authority-selected executable\n"
            if $artifact->{sha256} ne $current;
        next unless defined $root;
        die "sealed $identity_field path is malformed\n"
            unless defined($artifact->{path}) && !ref($artifact->{path})
                && !File::Spec->file_name_is_absolute($artifact->{path});
        my $sealed = File::Spec->catfile($root,
            File::Spec->splitdir($artifact->{path}));
        my $absolute_root = abs_path($root);
        my $absolute_sealed = abs_path($sealed);
        die "sealed $identity_field path escapes evidence root\n"
            unless $absolute_root && $absolute_sealed
                && path_inside($absolute_sealed, $absolute_root);
        die "sealed $identity_field snapshot changed before publication\n"
            unless sha256_file($absolute_sealed) eq $current;
    }
    return 1;
}

sub closed_checker_environment {
    my ($java) = @_;
    my %environment = (
        PATH => '', LANG => 'C', LC_ALL => 'C', TZ => 'UTC',
    );
    $environment{PERLONJAVA_JAVA_BIN} = $java if defined $java;
    return %environment;
}

sub terminate_group {
    my ($pid, $leader_reaped) = @_;
    kill 'TERM', -$pid;
    select undef, undef, undef, 0.2;
    kill 'KILL', -$pid;
    waitpid($pid, 0) unless $leader_reaped;
}

sub positive_number {
    my ($value) = @_;
    return number($value) && $value > 0;
}

sub true_value {
    my ($value) = @_;
    return defined($value) && "$value" eq '1';
}

sub canonical { JSON::PP->new->canonical->encode($_[0]) }

1;
