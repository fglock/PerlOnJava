use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin qw($Bin);
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($Bin, '..', '..', '..'));
my $wrapper = File::Spec->catfile($root, 'dev', 'tools',
    'check_phase36_release_manifest.pl');
my $verifier = File::Spec->catfile($root, 'dev', 'tools',
    'verify_phase36_notice_license.pl');
my $legacy_checker = File::Spec->catfile($root, 'dev', 'tools',
    'check_phase36_acceptance_manifest.pl');
my $temporary = tempdir(CLEANUP => 1);
my $fork_ref = 'pkg:generic/perlonjava/joni-fork@2.2.7';
my $legacy_ref = 'pkg:maven/org.jruby.joni/joni@2.2.7?type=jar';
my $jcodings_ref = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';
my $source_commit = '1234567890abcdef1234567890abcdef12345678';
my $json = JSON::PP->new->canonical->pretty;

do $wrapper or die "Cannot load $wrapper: $@ $!";
is(index(read_file($wrapper), "\0"), -1,
    'committed release wrapper source contains no NUL bytes');

subtest 'strict verifier artifact is independently reopened and verified' => sub {
    my $fixture = strict_fixture('green');
    my $result = verify_strict_notice_artifact(
        $fixture->{evidence_path}, $fixture->{evidence}, $source_commit);
    ok($result->{verified}, 'strict artifact passes independent verification');
    is($result->{contract}, 'joni-fork-strict-v1',
        'wrapper records the strict fork contract');
    is($result->{artifact_sha256}, sha_file($fixture->{artifact}),
        'wrapper recomputes the verifier artifact hash');
    is($result->{jar_sha256}, sha_file($fixture->{jar}),
        'wrapper recomputes the standalone JAR hash');
    is($result->{sbom_sha256}, sha_file($fixture->{sbom}),
        'wrapper recomputes the external SBOM hash');
};

subtest 'artifact must remain under the sealed evidence root' => sub {
    my $fixture = strict_fixture('outside-root');
    my $outside = File::Spec->catfile($temporary, 'outside-artifact.json');
    write_file($outside, read_file($fixture->{artifact}));
    $fixture->{evidence}{gates}{'notice-license'}{artifact} = {
        path => $outside, sha256 => sha_file($outside),
    };
    rejected($fixture, qr/outside the sealed evidence root/,
        'artifact path escape');
};

subtest 'gate details must be the exact sealed verifier JSON' => sub {
    my $fixture = strict_fixture('details-substitution');
    delete $fixture->{evidence}{gates}{'notice-license'}{details}{relationships};
    rejected($fixture, qr/gate details differ from the sealed verifier artifact/,
        'details substitution');
};

subtest 'a lookalike report must replay through the unchanged strict verifier' => sub {
    my $fixture = strict_fixture('strict-replay');
    my $record = read_json($fixture->{artifact});
    my $empty_source = File::Spec->catdir($fixture->{base}, 'empty-source');
    make_path($empty_source);
    $record->{source_root} = $empty_source;
    reseal_record($fixture, $record);
    rejected($fixture, qr/Strict notice-license verifier replay rejected/,
        'strict verifier replay failure');
};

subtest 'legacy Joni identity cannot be laundered into the verifier report' => sub {
    my $fixture = strict_fixture('legacy-report');
    my $record = read_json($fixture->{artifact});
    my ($joni) = grep { ($_->{name} // '') eq 'joni-fork' }
        @{$record->{components}};
    $joni->{group} = 'org.jruby.joni';
    $joni->{name} = 'joni';
    $joni->{bom_ref} = $legacy_ref;
    $joni->{purl} = $legacy_ref;
    for my $relation (@{$record->{relationships}}) {
        $relation->{from} = $legacy_ref if $relation->{from} eq $fork_ref;
        $relation->{to} = $legacy_ref if $relation->{to} eq $fork_ref;
    }
    reseal_record($fixture, $record);
    rejected($fixture, qr/(?:missing or duplicates joni-fork|legacy Maven Joni identity)/,
        'legacy report identity');
};

subtest 'external fork provenance and exact relationships are fail closed' => sub {
    my $missing = strict_fixture('missing-provenance');
    mutate_sbom_and_reseal($missing, sub {
        my ($sbom) = @_;
        my ($fork) = grep { ($_->{'bom-ref'} // '') eq $fork_ref }
            @{$sbom->{components}};
        @{$fork->{properties}} = grep {
            ($_->{name} // '') ne 'perlonjava:upstream-commit'
        } @{$fork->{properties}};
    });
    rejected($missing, qr/missing or duplicate perlonjava:upstream-commit property/,
        'missing provenance');

    my $edge = strict_fixture('extra-jcodings-edge');
    mutate_sbom_and_reseal($edge, sub {
        my ($sbom) = @_;
        my ($relation) = grep { ($_->{ref} // '') eq $fork_ref }
            @{$sbom->{dependencies}};
        push @{$relation->{dependsOn}}, 'pkg:generic/unexpected@1';
    });
    rejected($edge, qr/relationship is not exact/, 'extra fork relationship edge');
};

subtest 'embedded SBOM presence and bytes are independently verified' => sub {
    my $missing = strict_fixture('missing-embedded-after-verification');
    rebuild_jar($missing, omit_embedded => 1);
    reseal_record($missing, read_json($missing->{artifact}));
    rejected($missing, qr/(?:missing|must contain exactly one) META-INF\/sbom\/sbom\.json/,
        'missing embedded SBOM');

    my $different = strict_fixture('different-embedded-after-verification');
    write_file($different->{sbom}, "{\"mutated\":true}\n");
    reseal_record($different, read_json($different->{artifact}));
    rejected($different, qr/Invalid external SBOM JSON|External SBOM is not CycloneDX|embedded SBOM bytes differ/,
        'external and embedded SBOM mismatch');
};

subtest 'sealed JAR and SBOM hashes cannot be substituted' => sub {
    my $fixture = strict_fixture('wrong-sealed-hash');
    $fixture->{evidence}{identity}{jar_sha256} = 'a' x 64;
    rejected($fixture, qr/Notice-license report JAR hash differs from sealed identity/,
        'sealed JAR hash substitution');
};

subtest 'self-consistent alternate provenance commit is rejected' => sub {
    my $fixture = strict_fixture('alternate-provenance-commit');
    my $alternate = 'abcdef0123456789abcdef0123456789abcdef01';
    mutate_sbom_and_reseal($fixture, sub {
        my ($sbom) = @_;
        my ($fork) = grep { ($_->{'bom-ref'} // '') eq $fork_ref }
            @{$sbom->{components}};
        my ($property) = grep {
            ($_->{name} // '') eq 'perlonjava:source-commit'
        } @{$fork->{properties}};
        $property->{value} = $alternate;
    });
    my $output = File::Spec->catfile($fixture->{base}, 'must-not-exist.json');
    my $error;
    {
        no warnings 'redefine';
        local *main::run_legacy_checker = sub {
            return {
                check_mode => 'strict', expected_commit => $source_commit,
                summary => { authoritative => JSON::PP::true },
            };
        };
        eval { main('--evidence', $fixture->{evidence_path},
            '--expected-commit', $source_commit, '--output', $output) };
        $error = $@;
    }
    like($error, qr/wrong perlonjava:source-commit property/,
        'alternate valid provenance SHA is rejected by the final wrapper');
    ok(!-e $output, 'alternate provenance SHA publishes no authoritative report');
};

subtest 'authoritative output requires both validation layers' => sub {
    my $fixture = strict_fixture('final-flow');
    my $output = File::Spec->catfile($fixture->{base}, 'release-report.json');
    {
        no warnings 'redefine';
        local *main::run_legacy_checker = sub {
            return {
                check_mode => 'strict', expected_commit => $source_commit,
                summary => { authoritative => JSON::PP::true },
            };
        };
        is(main('--evidence', $fixture->{evidence_path},
                '--expected-commit', $source_commit, '--output', $output), 0,
            'final flow succeeds when both layers pass');
    }
    my $report = read_json($output);
    ok($report->{authoritative}, 'successful final flow is authoritative');
    is($report->{strict_notice_license}{contract}, 'joni-fork-strict-v1',
        'authoritative report identifies the strict contract');

    my $failure = strict_fixture('final-flow-strict-failure');
    my $failed_output = File::Spec->catfile($failure->{base}, 'must-not-exist.json');
    my $error;
    {
        no warnings 'redefine';
        local *main::run_legacy_checker = sub {
            return {
                check_mode => 'strict', expected_commit => $source_commit,
                summary => { authoritative => JSON::PP::true },
            };
        };
        local *main::verify_strict_notice_artifact = sub {
            die "injected strict artifact failure\n";
        };
        eval { main('--evidence', $failure->{evidence_path},
            '--expected-commit', $source_commit, '--output', $failed_output) };
        $error = $@;
    }
    like($error, qr/injected strict artifact failure/,
        'strict artifact failure propagates');
    ok(!-e $failed_output, 'strict artifact failure publishes no report');
};

subtest 'the executable wrapper fails before publication when legacy strict validation fails' => sub {
    my $base = File::Spec->catdir($temporary, 'legacy-failure');
    make_path($base);
    my $evidence = write_file(File::Spec->catfile($base, 'invalid.json'),
        $json->encode({ schema_version => 1, mode => 'acceptance' }));
    my $output = File::Spec->catfile($base, 'must-not-exist.json');
    my ($status, $text) = capture($^X, $wrapper,
        '--evidence', $evidence, '--expected-commit', $source_commit,
        '--output', $output);
    isnt($status, 0, 'legacy strict rejection fails the final wrapper');
    like($text, qr/Legacy acceptance checker rejected the release manifest/,
        'legacy rejection has a fail-closed diagnostic');
    ok(!-e $output, 'legacy rejection publishes no authoritative report');
};

subtest 'pinned reads reject evidence, artifact, JAR, and SBOM replacement races' => sub {
    my @cases = (
        [evidence => sub { $_[0]{evidence_path} }],
        [artifact => sub { $_[0]{artifact} }],
        [jar => sub { $_[0]{jar} }],
        [sbom => sub { $_[0]{sbom} }],
    );
    for my $case (@cases) {
        my ($name, $target_for) = @$case;
        my $fixture = strict_fixture("race-$name");
        my $target = abs_path($target_for->($fixture));
        my $replacement = File::Spec->catfile($temporary, "race-$name-replacement");
        write_file($replacement, "replacement $name bytes\n");
        my $swapped = 0;
        my $error;
        {
            no warnings 'once';
            local $main::PIN_OBSERVER = sub {
                my ($phase, $path) = @_;
                return unless !$swapped && $phase eq 'opened' && $path eq $target;
                unlink $path or die "cannot unlink race target $path: $!";
                rename $replacement, $path or die "cannot install race replacement: $!";
                $swapped = 1;
            };
            eval {
                my $sealed = seal_evidence($fixture->{evidence_path});
                my $document = decode_json_object($sealed->{evidence_bytes},
                    'acceptance evidence', $fixture->{evidence_path});
                verify_strict_notice_artifact($fixture->{evidence_path}, $document,
                    $source_commit, $sealed);
            };
            $error = $@;
        }
        ok($swapped, "$name replacement hook reached the pinned open boundary");
        like($error, qr/(?:changed|disappeared) while it was pinned/,
            "$name replacement race is rejected");
    }
};

subtest 'non-notice gate traversal and symlink escapes are rejected' => sub {
    my $base = File::Spec->catdir($temporary, 'non-notice-escape');
    make_path($base);
    my $outside = write_file(File::Spec->catfile($temporary, 'outside-gate.log'),
        "outside\n");
    my $traversal = {
        gates => { jvm => { artifact => {
            path => '../outside-gate.log', sha256 => sha_file($outside),
        } } },
    };
    my $error = eval { assert_legacy_artifacts_confined($traversal, $base); '' };
    $error = $@ if $@;
    like($error, qr/outside the sealed evidence root/,
        'non-notice parent traversal is rejected before legacy execution');

    my $link = File::Spec->catfile($base, 'jvm.log');
    if (symlink($outside, $link)) {
        my $symlink = {
            gates => { jvm => { artifact => {
                path => 'jvm.log', sha256 => sha_file($outside),
            } } },
        };
        $error = eval { assert_legacy_artifacts_confined($symlink, $base); '' };
        $error = $@ if $@;
        like($error, qr/must not be a symlink/,
            'non-notice symlink escape is rejected before legacy execution');
    } else {
        fail("cannot create symlink escape fixture: $!");
    }
};

subtest 'atomic publication removes every failed temporary output' => sub {
    my @boundaries = qw(print flush close rename link);
    my $real_print = \&main::checked_print;
    my $real_flush = \&main::checked_flush;
    my $real_close = \&main::checked_close;
    my $real_rename = \&main::checked_rename;
    my $real_link = \&main::checked_link;
    for my $boundary (@boundaries) {
        my $base = File::Spec->catdir($temporary, "publish-$boundary");
        make_path($base);
        my $output = File::Spec->catfile($base, 'release.json');
        my $error;
        {
            no warnings 'redefine';
            no warnings 'once';
            local *main::checked_print = $boundary eq 'print'
                ? sub { die "injected print failure\n" } : $real_print;
            local *main::checked_flush = $boundary eq 'flush'
                ? sub { die "injected flush failure\n" } : $real_flush;
            local *main::checked_close = $boundary eq 'close'
                ? sub { CORE::close($_[0]); die "injected close failure\n" }
                : $real_close;
            local *main::checked_rename = $boundary eq 'rename'
                ? sub { die "injected rename failure\n" } : $real_rename;
            local *main::checked_link = $boundary eq 'link'
                ? sub { die "injected link failure\n" } : $real_link;
            eval { publish_atomic($output, "{\"authoritative\":true}\n") };
            $error = $@;
        }
        like($error, qr/injected $boundary failure/,
            "$boundary failure propagates");
        ok(!-e $output, "$boundary failure leaves no authoritative output");
        opendir my $dh, $base or die "Cannot inspect $base: $!";
        my @temporary = grep { /\.tmp\./ } readdir $dh;
        closedir $dh;
        is_deeply(\@temporary, [], "$boundary failure cleans partial temporary files");
    }
};

subtest 'stdout write and finalization failures are explicit' => sub {
    for my $boundary (qw(print flush close)) {
        my $fixture = strict_fixture("stdout-$boundary");
        my $error;
        my $real_print = \&main::checked_print;
        my $real_flush = \&main::checked_flush;
        my $real_close = \&main::checked_close;
        {
            no warnings 'redefine';
            no warnings 'once';
            local *main::run_legacy_checker = sub {
                return { check_mode => 'strict', expected_commit => $source_commit,
                    summary => { authoritative => JSON::PP::true } };
            };
            local *main::verify_strict_notice_artifact = sub { return { verified => 1 } };
            local *main::checked_print = sub {
                die "injected stdout print failure\n"
                    if $boundary eq 'print' && $_[2] eq 'standard output';
                return $real_print->(@_);
            };
            local *main::checked_flush = sub {
                die "injected stdout flush failure\n"
                    if $boundary eq 'flush' && $_[1] eq 'standard output';
                return $real_flush->(@_);
            };
            local *main::checked_close = sub {
                if ($boundary eq 'close' && $_[1] eq 'standard output') {
                    CORE::close($_[0]);
                    die "injected stdout close failure\n";
                }
                return $real_close->(@_);
            };
            local $main::EXECUTABLE = 1;
            local *STDOUT;
            open STDOUT, '>', \my $captured or die "Cannot capture stdout: $!";
            eval { main('--evidence', $fixture->{evidence_path},
                '--expected-commit', $source_commit) };
            $error = $@;
        }
        like($error, qr/injected stdout $boundary failure/,
            "stdout $boundary failure propagates");
    }
};

subtest 'the real legacy checker accepts a valid strict fixture' => sub {
    my $base = File::Spec->catdir($temporary, 'real-legacy-success');
    make_path($base);
    my $artifact = write_file(File::Spec->catfile($base, 'make.log'), "make passed\n");
    my $baseline = '9' x 64;
    my $requirements = write_file(File::Spec->catfile($base, 'requirements.json'),
        $json->encode({
            schema_version => 1,
            policy => 'current upstream; no pinned Perl revision',
            baseline_sha256 => $baseline,
            allowed_cpan_excluded_audit_classifications => ['pre-existing-non-regex'],
            cpan_acceptance => {
                policy_sha256 => '8' x 64,
                expected_targets => ['Fixture'], required_modes => [qw(jvm interpreter)],
            },
            required_gates => [{ id => 'make', kind => 'make' }],
        }));
    my $evidence = write_file(File::Spec->catfile($base, 'evidence.json'),
        $json->encode({
            schema_version => 1, mode => 'acceptance',
            identity => {
                source_commit => $source_commit, perl5_commit => '2' x 40,
                runner_commit => $source_commit, jperl_sha256 => '3' x 64,
                jar_sha256 => '4' x 64, sbom_sha256 => '5' x 64,
                baseline_sha256 => $baseline,
            },
            gates => { make => {
                state => 'passed',
                artifact => { path => 'make.log', sha256 => sha_file($artifact) },
                identity => { source_commit => $source_commit },
                details => { passed => JSON::PP::true, warnings => 0, failures => 0 },
            } },
        }));
    my $report = File::Spec->catfile($base, 'report.json');
    my ($status, $text) = capture($^X, $legacy_checker,
        '--requirements', $requirements, '--evidence', $evidence,
        '--mode', 'strict', '--expected-commit', $source_commit,
        '--output', $report);
    is($status, 0, 'actual unchanged legacy checker exits successfully');
    ok(-f $report && read_json($report)->{summary}{authoritative},
        'actual unchanged legacy checker emits an authoritative strict report');
    is($text, '', 'successful real legacy checker invocation is quiet');
};

done_testing;

sub strict_fixture {
    my ($name) = @_;
    my $base = File::Spec->catdir($temporary, $name);
    my $tree = File::Spec->catdir($base, 'tree');
    make_path(File::Spec->catdir($tree, 'META-INF', 'licenses'));
    make_path(File::Spec->catdir($tree, 'META-INF', 'sbom'));
    my $sbom_document = strict_sbom();
    my $sbom = write_file(File::Spec->catfile($base, 'sbom.json'),
        $json->encode($sbom_document));
    copy_contract_files($tree);
    write_file(File::Spec->catfile($tree, 'META-INF', 'sbom', 'sbom.json'),
        read_file($sbom));
    my $jar = File::Spec->catfile($base, 'standalone.jar');
    system('jar', 'cf', $jar, '-C', $tree, '.') == 0 or die 'jar failed';
    my $artifact = File::Spec->catfile($base, 'notice-license.json');
    my ($status, $text) = capture($^X, $verifier, '--strict',
        '--source-root', $root, '--jar', $jar, '--sbom', $sbom,
        '--output', $artifact);
    die "strict verifier fixture failed: $text" if $status;
    my $record = read_json($artifact);
    my $evidence_path = File::Spec->catfile($base, 'acceptance.json');
    my $evidence = {
        schema_version => 1,
        mode => 'acceptance',
        identity => {
            source_commit => $source_commit,
            jar_sha256 => sha_file($jar),
            sbom_sha256 => sha_file($sbom),
        },
        gates => {
            'notice-license' => {
                state => 'passed',
                artifact => {
                    path => 'notice-license.json',
                    sha256 => sha_file($artifact),
                },
                details => $record,
            },
        },
    };
    write_file($evidence_path, $json->encode($evidence));
    return {
        base => $base, tree => $tree, jar => $jar, sbom => $sbom,
        artifact => $artifact, evidence => $evidence,
        evidence_path => $evidence_path,
    };
}

sub strict_sbom {
    return {
        bomFormat => 'CycloneDX',
        metadata => { component => { 'bom-ref' => 'perlonjava' } },
        components => [
            {
                type => 'library', group => 'org.perlonjava.fork',
                name => 'joni-fork', version => '2.2.7',
                'bom-ref' => $fork_ref, purl => $fork_ref,
                licenses => [{ license => { id => 'MIT' } }],
                properties => [
                    { name => 'perlonjava:vendored', value => 'true' },
                    { name => 'perlonjava:modified', value => 'true' },
                    { name => 'perlonjava:vendored-source-path',
                        value => 'third_party/joni' },
                    { name => 'perlonjava:source-commit', value => $source_commit },
                    { name => 'perlonjava:upstream-maven-coordinate',
                        value => 'org.jruby.joni:joni:2.2.7' },
                    { name => 'perlonjava:upstream-tag', value => 'joni-2.2.7' },
                    { name => 'perlonjava:upstream-commit',
                        value => '57fd57b4f977813a7b4b35e0179943b1f06f51d7' },
                ],
            },
            {
                type => 'library', group => 'org.jruby.jcodings',
                name => 'jcodings', version => '1.0.64',
                'bom-ref' => $jcodings_ref, purl => $jcodings_ref,
                licenses => [{ license => { id => 'MIT' } }],
            },
        ],
        dependencies => [
            { ref => 'perlonjava', dependsOn => [$fork_ref, $jcodings_ref] },
            { ref => $fork_ref, dependsOn => [$jcodings_ref] },
        ],
    };
}

sub copy_contract_files {
    my ($tree) = @_;
    my %files = (
        'joni-LICENSE.txt' => File::Spec->catfile($root, 'third_party', 'joni', 'LICENSE'),
        'joni-PERLONJAVA-NOTICE.md' => File::Spec->catfile(
            $root, 'third_party', 'joni', 'PERLONJAVA-NOTICE.md'),
        'jcodings-LICENSE.txt' => File::Spec->catfile(
            $root, 'third_party', 'licenses', 'jcodings-LICENSE.txt'),
    );
    for my $name (keys %files) {
        write_file(File::Spec->catfile($tree, 'META-INF', 'licenses', $name),
            read_file($files{$name}));
    }
}

sub mutate_sbom_and_reseal {
    my ($fixture, $mutate) = @_;
    my $sbom = read_json($fixture->{sbom});
    $mutate->($sbom);
    write_file($fixture->{sbom}, $json->encode($sbom));
    rebuild_jar($fixture);
    reseal_record($fixture, read_json($fixture->{artifact}));
}

sub rebuild_jar {
    my ($fixture, %option) = @_;
    my $embedded = File::Spec->catfile(
        $fixture->{tree}, 'META-INF', 'sbom', 'sbom.json');
    if ($option{omit_embedded}) {
        unlink $embedded or die "Cannot remove $embedded: $!";
    } else {
        write_file($embedded, read_file($fixture->{sbom}));
    }
    unlink $fixture->{jar} or die "Cannot replace $fixture->{jar}: $!";
    system('jar', 'cf', $fixture->{jar}, '-C', $fixture->{tree}, '.') == 0
        or die 'jar rebuild failed';
}

sub reseal_record {
    my ($fixture, $record) = @_;
    $record->{jar_sha256} = sha_file($fixture->{jar});
    $record->{sbom_sha256} = sha_file($fixture->{sbom});
    write_file($fixture->{artifact}, $json->encode($record));
    $fixture->{evidence}{identity}{jar_sha256} = $record->{jar_sha256};
    $fixture->{evidence}{identity}{sbom_sha256} = $record->{sbom_sha256};
    $fixture->{evidence}{gates}{'notice-license'}{artifact}{sha256}
        = sha_file($fixture->{artifact});
    $fixture->{evidence}{gates}{'notice-license'}{details} = $record;
    write_file($fixture->{evidence_path}, $json->encode($fixture->{evidence}));
}

sub rejected {
    my ($fixture, $pattern, $name) = @_;
    my $error = eval {
        verify_strict_notice_artifact(
            $fixture->{evidence_path}, $fixture->{evidence}, $source_commit);
        '';
    };
    $error = $@ if $@;
    like($error, $pattern, "$name is rejected");
}

sub capture {
    my (@command) = @_;
    pipe my $read, my $write or die "pipe: $!";
    my $pid = fork();
    die "fork: $!" unless defined $pid;
    if ($pid == 0) {
        close $read;
        open STDOUT, '>&', $write or die $!;
        open STDERR, '>&', $write or die $!;
        exec { $command[0] } @command;
        die "exec: $!";
    }
    close $write;
    my $text = do { local $/; <$read> };
    close $read;
    waitpid($pid, 0);
    return ($? >> 8, $text);
}

sub sha_file {
    return sha256_hex(read_file($_[0]));
}

sub write_file {
    my ($path, $contents) = @_;
    my (undef, $directory) = File::Spec->splitpath($path);
    make_path($directory) if length($directory) && !-d $directory;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!";
    return $path;
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!";
    return $contents;
}

sub read_json {
    return JSON::PP->new->decode(read_file($_[0]));
}
