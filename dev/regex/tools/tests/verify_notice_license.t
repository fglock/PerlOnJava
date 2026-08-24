use strict;
use warnings;

use Config;
use Digest::SHA qw(sha256_hex);
use File::Find qw(find);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IO::Compress::Zip qw($ZipError);
use JSON::PP;
use Test::More;

my $repository = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($repository, 'dev', 'regex', 'tools',
    'verify_notice_license.pl');
my $temporary = tempdir(CLEANUP => 1);
my $joni_ref = 'pkg:maven/org.jruby.joni/joni@2.2.7?type=jar';
my $jcodings_ref = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';

subtest 'green source, JAR, and merged SBOM emit canonical gate details' => sub {
    my ($source, $jar, $sbom, $output) = fixture('green');
    my ($status, $text) = run_tool($source, $jar, $sbom, $output);
    is($status, 0, 'green notice/license artifact verifies');
    is($text, "$output\n", 'tool reports output path');
    my $record = load_json($output);
    ok($record->{verified}, 'canonical record is verified');
    for my $field (qw(missing_notices changed_notices missing_licenses changed_licenses)) {
        is($record->{$field}, 0, "$field is zero");
    }
    like($record->{jar_sha256}, qr/^[0-9a-f]{64}$/, 'JAR hash retained');
    like($record->{sbom_sha256}, qr/^[0-9a-f]{64}$/, 'SBOM hash retained');
    is(scalar @{$record->{notices}}, 3, 'three canonical notice/license files retained');
    is_deeply([map { $_->{name} } @{$record->{components}}], [qw(joni jcodings)],
        'canonical vendored component identities retained');
    acceptance_checker_accepts($record, base_path_for_output($output));
};

subtest 'source notice and authorship failures are fail-closed' => sub {
    for my $case (
        ['missing-source', { remove_source => 'joni-license' }, qr/missing or empty/],
        ['blank-source', { blank_source => 'joni-notice' }, qr/missing or empty/],
        ['missing-authorship', { strip_authorship => 'jcodings-license' },
            qr/missing required authorship\/license material/],
    ) {
        my ($source, $jar, $sbom, $output) = fixture($case->[0], %{$case->[1]});
        rejected($source, $jar, $sbom, $output, $case->[2], $case->[0]);
    }
};

subtest 'JAR notice failure families are fail-closed' => sub {
    for my $case (
        ['missing-jar-notice', { omit_entry => 'joni-license' }, qr/JAR is missing/],
        ['changed-jar-notice', { wrong_entry => 'joni-notice' }, qr/notice bytes differ/],
        ['duplicate-jar-notice', { duplicate_entry => 'jcodings-license' },
            qr/JAR has duplicate/],
    ) {
        my ($source, $jar, $sbom, $output) = fixture($case->[0], %{$case->[1]});
        rejected($source, $jar, $sbom, $output, $case->[2], $case->[0]);
    }
};

subtest 'SBOM component, license, authorship, and relation failures are fail-closed' => sub {
    my @cases = (
        ['malformed', sub { return '{ bad' }, qr/Malformed SBOM JSON/],
        ['dependency-only', sub { my $d = shift; $d->{components} = []; $d },
            qr/dependency-only BOM/],
        ['duplicate-component', sub { my $d = shift; push @{$d->{components}},
            {%{$d->{components}[0]}}; $d }, qr/duplicate (?:bom-ref|purl)/],
        ['wrong-license', sub { my $d = shift;
            $d->{components}[0]{licenses}[0]{license}{id} = 'Apache-2.0'; $d },
            qr/wrong or missing joni license/],
        ['missing-authorship-property', sub { my $d = shift;
            delete $d->{components}[0]{properties}; $d },
            qr/missing Joni vendored authorship metadata/],
        ['missing-root-edge', sub { my $d = shift;
            $d->{dependencies}[0]{dependsOn} = []; $d }, qr/missing root -> Joni/],
        ['duplicate-joni-relation', sub { my $d = shift;
            push @{$d->{dependencies}}, {%{$d->{dependencies}[1]}}; $d },
            qr/duplicate Joni -> JCodings dependency relations/],
        ['malformed-joni-relation', sub { my $d = shift;
            $d->{dependencies}[1]{dependsOn} = {}; $d },
            qr/dependency relation is malformed/],
        ['missing-jcodings-edge', sub { my $d = shift;
            $d->{dependencies}[1]{dependsOn} = []; $d },
            qr/missing Joni -> JCodings dependency edge/],
    );
    for my $case (@cases) {
        my ($source, $jar, $sbom, $output) = fixture($case->[0], sbom => $case->[1]);
        rejected($source, $jar, $sbom, $output, $case->[2], $case->[0]);
    }
};

subtest 'output collision is rejected' => sub {
    my ($source, $jar, $sbom, $output) = fixture('output-collision');
    write_file($output, 'occupied');
    rejected($source, $jar, $sbom, $output, qr/Refusing to overwrite output/,
        'output collision');
};

subtest 'protected artifact mutation is rejected before publication' => sub {
    my ($source, $jar, $sbom, $output) = fixture('artifact-mutation');
    my $bin = File::Spec->catdir($temporary, 'mutation-bin');
    make_path($bin);
    my $real_jar = executable_in_path('jar');
    my $wrapper_program = File::Spec->catfile($bin, 'jar-wrapper.pl');
    write_file($wrapper_program, <<'WRAPPER');
#!/usr/bin/env perl
use strict;
use warnings;
if (@ARGV && $ARGV[0] eq 'tf' && !$ENV{NOTICE_MUTATED}++) {
    open my $fh, '>>:raw', $ENV{NOTICE_MUTATE_FILE} or die $!;
    print {$fh} " \n";
    close $fh;
}
exec { $ENV{NOTICE_REAL_JAR} } $ENV{NOTICE_REAL_JAR}, @ARGV;
die $!;
WRAPPER
    my $wrapper;
    if ($^O eq 'MSWin32') {
        $wrapper = File::Spec->catfile($bin, 'jar.cmd');
        write_file($wrapper, qq{\@"$^X" "$wrapper_program" %*\r\n});
    } else {
        $wrapper = File::Spec->catfile($bin, 'jar');
        write_file($wrapper, read_file($wrapper_program));
        chmod 0755, $wrapper or die $!;
    }
    my $original_path_ext = $ENV{PATHEXT};
    local $ENV{PATHEXT} = $original_path_ext;
    if ($^O eq 'MSWin32') {
        my @extensions = split /;/,
            ($original_path_ext // '.COM;.EXE;.BAT;.CMD');
        $ENV{PATHEXT} = join ';', '.CMD',
            grep { !/\A\.CMD\z/i } @extensions;
    }
    local $ENV{PATH} = join $Config{path_sep}, $bin, ($ENV{PATH} // '');
    local $ENV{NOTICE_REAL_JAR} = $real_jar;
    local $ENV{NOTICE_MUTATE_FILE} = $sbom;
    local $ENV{NOTICE_MUTATED};
    rejected($source, $jar, $sbom, $output, qr/Merged SBOM mutated during verification/,
        'SBOM mutation');
};

done_testing;

sub fixture {
    my ($name, %option) = @_;
    my $base = File::Spec->catdir($temporary, $name);
    my $source = File::Spec->catdir($base, 'source');
    my $tree = File::Spec->catdir($base, 'tree');
    make_path(File::Spec->catdir($source, 'third_party', 'joni'));
    make_path(File::Spec->catdir($source, 'third_party', 'licenses'));
    make_path(File::Spec->catdir($tree, 'META-INF', 'licenses'));
    my %contract = contract_paths($source);
    my %repository_source = contract_paths($repository);
    for my $id (keys %contract) {
        my $contents = read_file($repository_source{$id}{source});
        $contents = '' if ($option{blank_source} // '') eq $id;
        $contents =~ s/Copyright[^\n]*\n//
            if ($option{strip_authorship} // '') eq $id;
        write_file($contract{$id}{source}, $contents)
            unless ($option{remove_source} // '') eq $id;
        next if ($option{omit_entry} // '') eq $id;
        my $entry_contents = $contents;
        $entry_contents = 'changed' if ($option{wrong_entry} // '') eq $id;
        write_file(File::Spec->catfile($tree, split m{/}, $contract{$id}{entry}),
            $entry_contents);
    }
    my $jar = File::Spec->catfile($base, 'standalone.jar');
    if ($option{duplicate_entry}) {
        create_zip($jar, $tree, $contract{$option{duplicate_entry}}{entry});
    } else {
        system('jar', 'cf', $jar, '-C', $tree, '.') == 0 or die 'jar failed';
    }
    my $document = valid_sbom();
    my $encoded;
    if ($option{sbom}) {
        my $changed = $option{sbom}->($document);
        $encoded = ref($changed) ? JSON::PP->new->canonical->encode($changed) : $changed;
    } else {
        $encoded = JSON::PP->new->canonical->encode($document);
    }
    my $sbom = write_file(File::Spec->catfile($base, 'sbom.json'), $encoded);
    my $output = File::Spec->catfile($base, 'notice-license.json');
    return ($source, $jar, $sbom, $output);
}

sub valid_sbom {
    return {
        bomFormat => 'CycloneDX',
        metadata => { component => { 'bom-ref' => 'perlonjava', name => 'perlonjava' } },
        components => [
            { type => 'library', group => 'org.jruby.joni', name => 'joni',
                version => '2.2.7', 'bom-ref' => $joni_ref, purl => $joni_ref,
                licenses => [{ license => { id => 'MIT' } }],
                properties => [{ name => 'perlonjava:vendored', value => 'true' }] },
            { type => 'library', group => 'org.jruby.jcodings', name => 'jcodings',
                version => '1.0.64', 'bom-ref' => $jcodings_ref, purl => $jcodings_ref,
                licenses => [{ license => { id => 'MIT' } }] },
        ],
        dependencies => [
            { ref => 'perlonjava', dependsOn => [$joni_ref, $jcodings_ref] },
            { ref => $joni_ref, dependsOn => [$jcodings_ref] },
        ],
    };
}

sub contract_paths {
    my ($root) = @_;
    return (
        'joni-license' => {
            source => File::Spec->catfile($root, 'third_party', 'joni', 'LICENSE'),
            entry => 'META-INF/licenses/joni-LICENSE.txt' },
        'joni-notice' => {
            source => File::Spec->catfile($root, 'third_party', 'joni',
                'PERLONJAVA-NOTICE.md'),
            entry => 'META-INF/licenses/joni-PERLONJAVA-NOTICE.md' },
        'jcodings-license' => {
            source => File::Spec->catfile($root, 'third_party', 'licenses',
                'jcodings-LICENSE.txt'),
            entry => 'META-INF/licenses/jcodings-LICENSE.txt' },
    );
}

sub create_zip {
    my ($jar, $tree, $duplicate) = @_;
    my @names;
    find({ no_chdir => 1, wanted => sub {
        return unless -f $_;
        my $name = File::Spec->abs2rel($_, $tree);
        $name =~ s{\\}{/}g;
        push @names, $name;
    }}, $tree);
    push @names, $duplicate;
    @names = sort @names;
    my $first = shift @names;
    my $zip = IO::Compress::Zip->new($jar, Name => $first)
        or die "Cannot create zip: $ZipError";
    print {$zip} read_file(File::Spec->catfile($tree, split m{/}, $first));
    for my $name (@names) {
        $zip->newStream(Name => $name) or die "Cannot add zip stream: $ZipError";
        print {$zip} read_file(File::Spec->catfile($tree, split m{/}, $name));
    }
    close $zip or die "Cannot close zip: $ZipError";
}

sub run_tool {
    my ($source, $jar, $sbom, $output) = @_;
    return capture($^X, $tool, '--source-root', $source, '--jar', $jar,
        '--sbom', $sbom, '--output', $output);
}

sub rejected {
    my ($source, $jar, $sbom, $output, $pattern, $name) = @_;
    my ($status, $text) = run_tool($source, $jar, $sbom, $output);
    isnt($status, 0, "$name is rejected");
    like($text, $pattern, "$name has a specific diagnostic");
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

sub executable_in_path {
    my ($name) = @_;
    my @suffixes = $^O eq 'MSWin32'
        ? grep { /\A\.(?:COM|EXE)\z/i }
            split /;/, ($ENV{PATHEXT} // '.COM;.EXE;.BAT;.CMD')
        : ('');
    for my $directory (split /\Q$Config{path_sep}\E/, ($ENV{PATH} // ''), -1) {
        $directory = File::Spec->curdir if $directory eq '';
        for my $suffix (@suffixes) {
            my $candidate = File::Spec->catfile($directory, "$name$suffix");
            return File::Spec->rel2abs($candidate) if -f $candidate && -x $candidate;
        }
    }
    die "Cannot find $name in PATH\n";
}

sub acceptance_checker_accepts {
    my ($notice, $directory) = @_;
    my $checker = File::Spec->catfile($repository, 'dev', 'regex', 'tools',
        'check_acceptance_manifest.pl');
    my $requirements = File::Spec->catfile($repository, 'dev', 'regex', 'tools',
        'acceptance_requirements.json');
    my $requirements_record = load_json($requirements);
    my $baseline = $requirements_record->{baseline_sha256};
    like($baseline, qr/\A[0-9a-f]{64}\z/,
        'fixture reads the authoritative baseline identity');
    my $artifact = write_file(File::Spec->catfile($directory, 'gate.artifact'),
        "retained gate evidence\n");
    my $artifact_descriptor = {
        path => $artifact,
        sha256 => sha256_hex(read_file($artifact)),
    };
    my $source = '1' x 40;
    my $perl5 = '2' x 40;
    my $jperl = '3' x 64;
    my %identity = (
        source_commit => $source, perl5_commit => $perl5,
        runner_commit => $source, jperl_sha256 => $jperl,
        jar_sha256 => $notice->{jar_sha256}, sbom_sha256 => $notice->{sbom_sha256},
        baseline_sha256 => $baseline,
    );
    my $gate = sub {
        my ($details, %extra_identity) = @_;
        return {
            state => 'passed', artifact => {%$artifact_descriptor},
            identity => { source_commit => $source, %extra_identity },
            details => $details,
        };
    };
    my %runner_identity = (runner_commit => $source, jperl_sha256 => $jperl);
    my %comparison_identity = (%runner_identity, baseline_sha256 => $baseline);
    my %comparison = (
        expected_files => 1, candidate_files => 1, regressions => 0,
        missing_files => 0, zero_tap => 0, timeouts => 0, truncated => 0,
        execution_issues => 0, wrong_executable => 0, wrong_commit => 0,
    );
    my $manifest = {
        schema_version => 1, mode => 'acceptance', identity => \%identity,
        gates => {
            ledger => $gate->({ scope => 'complete', runner_files => 1,
                direct_thread_pairs => 1, thread_only_tests => 0,
                unresolved_references => 0, missing_files => 0 }),
            jvm => $gate->({%comparison}, %comparison_identity),
            interpreter => $gate->({%comparison}, %comparison_identity),
            'direct-thread' => $gate->({ expected_pairs => 1, actual_pairs => 1,
                expected_modes => 4, actual_modes => 4, expected_thread_only => 0,
                actual_thread_only => 0, expected_thread_only_modes => 2,
                actual_thread_only_modes => 2, mismatches => 0, missing => 0,
                zero_tap => 0, timeouts => 0, truncated => 0,
                execution_issues => 0 }, %runner_identity),
            cpan => $gate->({ expected_targets => ['Fixture'],
                results => { Fixture => { status => 'pass', total_tests => 1 } },
                excluded_audits => [] }, %runner_identity),
            performance => $gate->({ baseline_seconds => [(2) x 5],
                candidate_seconds => [(1) x 5], alternating_order => JSON::PP::true }),
            packaging => $gate->({ verified => JSON::PP::true,
                jar_sha256 => $notice->{jar_sha256},
                sbom_sha256 => $notice->{sbom_sha256}, missing_entries => 0,
                duplicate_entries => 0 }),
            'notice-license' => $gate->($notice),
            make => $gate->({ passed => JSON::PP::true, warnings => 0, failures => 0 }),
            ci => $gate->({ platforms => {
                'ubuntu-latest' => { status => 'success', source_commit => $source },
                'windows-latest' => { status => 'success', source_commit => $source },
            }}),
        },
    };
    my $evidence = write_file(File::Spec->catfile($directory, 'acceptance.json'),
        JSON::PP->new->canonical->pretty->encode($manifest));
    my $report = File::Spec->catfile($directory, 'acceptance-report.json');
    system $^X, $checker, '--requirements', $requirements, '--evidence', $evidence,
        '--mode', 'report', '--expected-commit', $source, '--output', $report;
    is($? >> 8, 0, 'notice fixture reports without claiming whole-manifest acceptance');
    my $checked = load_json($report);
    is($checked->{gates}{'notice-license'}{status}, 'passed',
        'acceptance checker classifies emitted notice details as passed');

    my $mismatch = JSON::PP->new->decode(JSON::PP->new->encode($manifest));
    $mismatch->{identity}{baseline_sha256} = '0' x 64;
    my $mismatch_evidence = write_file(File::Spec->catfile($directory,
        'mismatched-baseline-acceptance.json'),
        JSON::PP->new->canonical->pretty->encode($mismatch));
    my $mismatch_report = File::Spec->catfile($directory,
        'mismatched-baseline-report.json');
    system $^X, $checker, '--requirements', $requirements,
        '--evidence', $mismatch_evidence, '--mode', 'report',
        '--expected-commit', $source, '--output', $mismatch_report;
    is($? >> 8, 0, 'mismatched baseline still produces a diagnostic report');
    my $mismatch_checked = load_json($mismatch_report);
    ok(grep({ $_ eq 'evidence baseline does not match the required baseline' }
        @{$mismatch_checked->{global_issues}}),
        'report-mode validation retains the fail-closed baseline mismatch issue');
}

sub base_path_for_output {
    my ($output) = @_;
    my ($volume, $directory) = File::Spec->splitpath($output);
    return File::Spec->catpath($volume, $directory, '');
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

sub load_json { JSON::PP->new->decode(read_file($_[0])) }
