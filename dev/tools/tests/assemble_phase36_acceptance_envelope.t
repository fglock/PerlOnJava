use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IPC::Open3;
use JSON::PP;
use MIME::Base64 qw(encode_base64);
use Symbol qw(gensym);
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools',
    'assemble_phase36_acceptance_envelope.pl');
my $requirements = $ENV{PHASE36_ACCEPTANCE_REQUIREMENTS}
    // File::Spec->catfile($root, 'dev', 'tools',
        'phase36_acceptance_requirements.json');
my $requirements_fixture_directory = tempdir(CLEANUP => 1);
my $requirements_document = JSON::PP->new->decode(do {
    open my $fh, '<:raw', $requirements or die "Cannot read $requirements: $!";
    local $/; <$fh>;
});
$requirements_document->{baseline_sha256} = sha256_hex("baseline\n");
$requirements = File::Spec->catfile($requirements_fixture_directory,
    'phase36_acceptance_requirements.json');
open my $requirements_fh, '>:raw', $requirements or die $!;
print {$requirements_fh} JSON::PP->new->canonical->pretty->encode(
    $requirements_document);
close $requirements_fh or die $!;
my $legacy_checker = $ENV{PHASE36_ACCEPTANCE_CHECKER}
    // File::Spec->catfile($root, 'dev', 'tools',
        'check_phase36_acceptance_manifest.pl');
my $json = JSON::PP->new->canonical->pretty;
my $source = '1' x 40;
my $perl5 = '2' x 40;
my $jperl = sha256_hex("jperl\n");
my $jar = sha256_hex("jar\n");
my $sbom = sha256_hex("{}\n");
my $baseline = sha256_hex("baseline\n");
my $policy = 'b35b479d260550f933c144205c4c0b940e4b3df8731609ff215f687cc1a74872';
my @gates = qw(ledger jvm interpreter direct-thread cpan performance
    packaging notice-license make ci);
my @targets = qw(DBIx::Class DateTime Moo Regexp::Common String::Random
    Template Type::Tiny WWW::Mechanize);

my ($compile_status, $compile_log) = run_command($^X, '-c', $tool);
is($compile_status, 0, 'assembler compiles with system Perl') or diag $compile_log;

{
    my $fixture = fixture();
    my ($status, $log) = run_assembler($fixture);
    is($status, 0, 'real-producer-shaped structured set assembles') or diag $log;
    my $envelope = read_json($fixture->{output});
    is($envelope->{schema_version}, 1, 'output uses legacy checker schema v1');
    is($envelope->{mode}, 'acceptance', 'output is acceptance evidence');
    is_deeply([sort keys %{$envelope->{gates}}], [sort @gates],
        'output has exactly the ten policy gates');
    is($envelope->{gates}{ledger}{details}{runner_files}, 7,
        'mutable discovered file count is translated, not pinned to 623');
    is($envelope->{gates}{jvm}{details}{candidate_files}, 7,
        'comparison uses the same current ledger count');
    is_deeply($envelope->{gates}{performance}{details}, {
        final_performance_contract => 'phase36-final-performance/v1',
        final_performance_sha256 =>
            $envelope->{gates}{performance}{artifact}{sha256},
        performance_authority => 'final-release-wrapper',
    }, 'performance authority is delegated only to the accepted final wrapper');

    my ($second_status, $second_log) = run_assembler($fixture);
    isnt($second_status, 0, 'existing output is never overwritten');
    like($second_log, qr/Refusing to overwrite output|exclusively publish/,
        'exclusive publication has a specific diagnostic');
}

{
    my $fixture = fixture();
    my ($assemble_status, $assemble_log) = run_assembler($fixture);
    is($assemble_status, 0, 'strict-consumption fixture assembles')
        or diag $assemble_log;
    SKIP: {
        my $checker_source = read_file($legacy_checker);
        skip 'checked-in parent checker predates the accepted A232 delegation', 1
            unless $checker_source =~ /final_performance_contract/;
        my $check_output = File::Spec->catfile($fixture->{directory},
            'strict-check.json');
        my ($check_status, $check_log) = run_command($^X, $legacy_checker,
            '--requirements', $requirements, '--evidence', $fixture->{output},
            '--mode', 'strict', '--expected-commit', $source,
            '--output', $check_output);
        is($check_status, 0, 'emitted fixture passes actual strict checker')
            or diag((-e $check_output ? read_file($check_output) : ''), $check_log);
    }
}

for my $gate (@gates) {
    my $missing = fixture();
    @{$missing->{authority}{lanes}} = grep { $_->{gate} ne $gate }
        @{$missing->{authority}{lanes}};
    rewrite_authority($missing);
    my ($missing_status, $missing_log) = run_assembler($missing);
    isnt($missing_status, 0, "missing $gate gate rejects");
    like($missing_log, qr/Missing authority gates:.*\Q$gate\E/,
        "missing $gate identifies the gate");

    my $tampered = fixture();
    my ($lane) = grep { $_->{gate} eq $gate } @{$tampered->{authority}{lanes}};
    my $artifact = File::Spec->catfile($tampered->{directory}, $lane->{artifact}{path});
    append_file($artifact, "tampered\n");
    my ($tamper_status, $tamper_log) = run_assembler($tampered);
    isnt($tamper_status, 0, "tampered $gate artifact rejects");
    like($tamper_log, qr/hash mismatch/,
        "tampered $gate is rejected by its selected hash");
}

{
    my $fixture = fixture();
    delete $fixture->{authority}{prerequisites};
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'missing latest-Perl sync prerequisite rejects');
    like($log, qr/Authority prerequisites must be an object/,
        'missing sync attachment is explicit');
}

{
    my $fixture = fixture();
    my $entry = $fixture->{authority}{prerequisites}{perl5_sync};
    append_file(File::Spec->catfile($fixture->{directory},
        $entry->{artifact}{path}), "tampered\n");
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'tampered latest-Perl sync prerequisite rejects');
    like($log, qr/Perl5 sync prerequisite artifact hash mismatch/,
        'sync attachment is hash-bound');
}

{
    my $fixture = fixture();
    push @{$fixture->{authority}{lanes}}, {%{$fixture->{authority}{lanes}[0]}};
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'duplicate gate selection rejects');
    like($log, qr/Duplicate authority gate/, 'duplicate diagnostic is exact');
}

{
    my $fixture = fixture();
    $fixture->{authority}{lanes}[0]{summary} = {state => 'passed'};
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'mixed legacy summary in authority rejects');
    like($log, qr/unsupported fields: summary/,
        'authority cannot self-declare a gate summary');
}

{
    my $fixture = fixture();
    $fixture->{authority}{lanes}[0]{artifact}{path} = '../outside.json';
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'parent-traversal artifact path rejects');
    like($log, qr/parent traversal/, 'unsafe path diagnostic is exact');
}

{
    my $fixture = fixture();
    $fixture->{authority}{identity}{source_commit} = 'a' x 40;
    $fixture->{authority}{identity}{runner_commit} = 'a' x 40;
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'stale authority source rejects');
    like($log, qr/source differs from --expected-candidate/,
        'candidate binding diagnostic is exact');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'make' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{completion}{timeout} = JSON::PP::true;
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'timeout record rejects even after authority rehash');
    like($log, qr/non-pass, incomplete, timed out, or truncated/,
        'timeout cannot be laundered by resealing');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'direct-thread' }
        @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{details}{zero_tap} = 1;
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'zero-TAP direct/thread record rejects after rehash');
    like($log, qr/incomplete or failing evidence/,
        'zero-TAP cannot be laundered by resealing');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'cpan' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{results}{Moo}{modes}{jvm}{total_tests} = 0;
    write_json($path, $record);
    write_file("$path.sha256", sha_file($path) . "  cpan-acceptance.json\n");
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'zero-TAP CPAN mode rejects after complete reseal');
    like($log, qr/CPAN target Moo jvm did not pass/,
        'structured producer data overrides a passing aggregate');
}

{
    my $fixture = fixture();
    my $sync = $fixture->{authority}{prerequisites}{perl5_sync};
    my $path = File::Spec->catfile($fixture->{directory}, $sync->{artifact}{path});
    my $record = read_json($path);
    delete $record->{upstream};
    write_json($path, $record);
    $sync->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'minimal latest-Perl summary without upstream rejects');
    like($log, qr/upstream identity is missing/, 'sync completeness is mandatory');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'performance' }
        @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{timeout} = JSON::PP::true;
    $record->{incomplete} = JSON::PP::true;
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'timed-out incomplete final-performance record rejects');
    like($log, qr/final performance producer has unsupported fields: incomplete, timeout/,
        'legacy failure fields cannot be mixed into final authority');
}

{
    my $fixture = fixture();
    my $raw = read_file($fixture->{authority_path});
    $raw =~ s/"mode"\s*:\s*"acceptance"/"mode":"report","mode":"acceptance"/
        or die 'cannot inject duplicate key';
    write_file($fixture->{authority_path}, $raw);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'duplicate JSON object key rejects');
    like($log, qr/duplicate object key 'mode'/,
        'duplicate-key rejection names the decoded key');
}

{
    my $fixture = fixture();
    $fixture->{authority}{summary} = {authoritative => JSON::PP::true};
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'top-level mixed authority summary rejects');
    like($log, qr/authority has unsupported fields: summary/,
        'top-level authority uses an exact key set');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'make' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{completion}{summary} = 'passed';
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'recursive extra completion key rejects');
    like($log, qr/make completion has unsupported fields: summary/,
        'recursive exact-key validation is active');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'direct-thread' }
        @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{details}{expected_pairs} = '9' x 19;
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'oversized decimal count rejects before coercion');
    like($log, qr/expected_pairs is missing/,
        'bounded numeric validator rejects excessive digits');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'packaging' }
        @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    delete $record->{artifacts};
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'report-only package summary rejects');
    like($log, qr/no structured hashed artifacts/,
        'gate summary cannot replace retained producer evidence');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'packaging' }
        @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{artifacts} = {placeholder => {}};
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'empty nested package artifact placeholder rejects');
    like($log, qr/packaging artifacts has unsupported fields: placeholder/,
        'package retention requires a real immutable descriptor leaf');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'ledger' } @{$fixture->{authority}{lanes}};
    my $regex_path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $regex = read_json($regex_path);
    my $ledger_path = File::Spec->catfile($fixture->{directory},
        $regex->{artifacts}{'regex-ledger.json'}{path});
    my $ledger = read_json($ledger_path);
    delete $ledger->{summary}{unresolved_references};
    write_json($ledger_path, $ledger);
    $regex->{artifacts}{'regex-ledger.json'}{sha256} = sha_file($ledger_path);
    write_json($regex_path, $regex);
    $_->{artifact}{sha256} = sha_file($regex_path)
        for grep { $_->{producer} eq 'run_phase36_regex_acceptance.pl' }
            @{$fixture->{authority}{lanes}};
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'missing ledger completeness field rejects');
    like($log, qr/ledger summary is incomplete/,
        'missing completeness is not synthesized as zero');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'ledger' } @{$fixture->{authority}{lanes}};
    my $regex_path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $regex = read_json($regex_path);
    my $runner_path = File::Spec->catfile($fixture->{directory},
        $regex->{artifacts}{'jvm-results.json'}{path});
    my $runner = read_json($runner_path);
    delete $runner->{results}{'test-1.t'}{planned_tests};
    write_json($runner_path, $runner);
    $regex->{artifacts}{'jvm-results.json'}{sha256} = sha_file($runner_path);
    write_json($regex_path, $regex);
    $_->{artifact}{sha256} = sha_file($regex_path)
        for grep { $_->{producer} eq 'run_phase36_regex_acceptance.pl' }
            @{$fixture->{authority}{lanes}};
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'missing planned TAP count rejects');
    like($log, qr/runner row is incomplete/,
        'runner completeness cannot be omitted');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'ledger' } @{$fixture->{authority}{lanes}};
    my $regex_path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $regex = read_json($regex_path);
    my $runner_path = File::Spec->catfile($fixture->{directory},
        $regex->{artifacts}{'jvm-results.json'}{path});
    my $runner = read_json($runner_path);
    $runner->{jperl_path} = '/untrusted/jperl';
    write_json($runner_path, $runner);
    $regex->{artifacts}{'jvm-results.json'}{sha256} = sha_file($runner_path);
    write_json($regex_path, $regex);
    $_->{artifact}{sha256} = sha_file($regex_path)
        for grep { $_->{producer} eq 'run_phase36_regex_acceptance.pl' }
            @{$fixture->{authority}{lanes}};
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'runner result from wrong executable rejects');
    like($log, qr/jvm runner used the wrong executable/,
        'wrong-executable count is evidence-backed rather than synthesized');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'ledger' } @{$fixture->{authority}{lanes}};
    my $regex_path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $regex = read_json($regex_path);
    my $version_path = File::Spec->catfile($fixture->{directory},
        $regex->{artifacts}{'jperl-version.log'}{path});
    write_file($version_path, "This is stale PerlOnJava (" . ('a' x 40) . ")\n");
    $regex->{artifacts}{'jperl-version.log'}{sha256} = sha_file($version_path);
    write_json($regex_path, $regex);
    $_->{artifact}{sha256} = sha_file($regex_path)
        for grep { $_->{producer} eq 'run_phase36_regex_acceptance.pl' }
            @{$fixture->{authority}{lanes}};
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'self-consistently resealed wrong runner commit rejects');
    like($log, qr/jperl-version log does not identify the trusted runner/,
        'wrong-commit count is backed by retained version output');
}

{
    my $fixture = fixture();
    my @arguments = assembler_arguments($fixture);
    for my $index (0 .. $#arguments - 1) {
        $arguments[$index + 1] = 'a' x 64
            if $arguments[$index] eq '--expected-sbom-sha256';
    }
    my ($status, $log) = run_command($^X, $tool, @arguments);
    isnt($status, 0, 'untrusted authority SBOM identity rejects');
    like($log, qr/sbom_sha256 differs from trusted CLI identity/,
        'global artifact identity is CLI-backed');
}

{
    my $fixture = fixture();
    my @arguments = assembler_arguments($fixture);
    for my $index (0 .. $#arguments - 1) {
        $arguments[$index + 1] = 'a' x 64
            if $arguments[$index] eq '--expected-authority-sha256';
    }
    my ($status, $log) = run_command($^X, $tool, @arguments);
    isnt($status, 0, 'authority manifest not matching trusted hash rejects');
    like($log, qr/authority hash mismatch/,
        'authority manifest is independently hash-bound');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'make' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $target = "$path.real";
    rename($path, $target) or die "Cannot rename fixture artifact: $!";
    symlink($target, $path) or die "Cannot create fixture symlink: $!";
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'symlink-selected producer artifact rejects');
    like($log, qr/gate make artifact is a symlink/,
        'immutable artifact ingestion rejects symlink replacement');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'cpan' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    delete $record->{identity}{manifest_sha256};
    write_json($path, $record);
    write_file("$path.sha256", sha_file($path) . "  cpan-acceptance.json\n");
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'CPAN report missing sealed manifest identity rejects');
    like($log, qr/CPAN manifest_sha256 is missing or malformed/,
        'CPAN translation requires complete sealed producer authority');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'cpan' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{mode} = 'acceptance';
    $record->{status} = 'pass';
    $record->{authority}{execution_authorized} = JSON::PP::false;
    $record->{identity}{execution_authorized} = JSON::PP::false;
    write_json($path, $record);
    write_file("$path.sha256", sha_file($path) . "  cpan-acceptance.json\n");
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0,
        'compatibility CPAN bundle remains rejected after relabel and reseal');
    like($log, qr/CPAN execution is not authorized/,
        'byte-integrity authority cannot launder false execution authority');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'cpan' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{authority}{execution_authorized} = 1;
    write_json($path, $record);
    write_file("$path.sha256", sha_file($path) . "  cpan-acceptance.json\n");
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'numeric CPAN execution authority rejects after reseal');
    like($log, qr/CPAN execution authority is not an exact JSON boolean/,
        'execution release requires an exact JSON boolean');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'cpan' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{identity}{execution_authorized} = 'true';
    write_json($path, $record);
    write_file("$path.sha256", sha_file($path) . "  cpan-acceptance.json\n");
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'string CPAN execution identity rejects after reseal');
    like($log, qr/CPAN execution identity is not an exact JSON boolean/,
        'consumer identity cannot coerce execution authority');
}

for my $case (
    ['authority false, identity true', 'authority', JSON::PP::false],
    ['authority true, identity false', 'identity', JSON::PP::false],
) {
    my ($name, $side, $value) = @$case;
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'cpan' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{$side}{execution_authorized} = $value;
    write_json($path, $record);
    write_file("$path.sha256", sha_file($path) . "  cpan-acceptance.json\n");
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, "$name rejects after complete reseal");
    like($log, qr/CPAN execution authorization differs between authority and identity/,
        "$name cannot pass the marker/bridge agreement boundary");
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'cpan' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    delete $record->{identity}{execution_authorized};
    write_json($path, $record);
    write_file("$path.sha256", sha_file($path) . "  cpan-acceptance.json\n");
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'missing CPAN execution identity rejects after reseal');
    like($log, qr/CPAN execution identity is not an exact JSON boolean/,
        'execution authority must be explicitly retained by the consumer');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'cpan' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{authority}{tuple_sha256} = sha256_hex('redirected tuple');
    write_json($path, $record);
    write_file("$path.sha256", sha_file($path) . "  cpan-acceptance.json\n");
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'CPAN marker/bridge tuple mismatch rejects after reseal');
    like($log, qr/CPAN marker\/bridge authority tuple binding differs: tuple_sha256/,
        'execution authority is bound to the selected marker and bridge tuple');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'cpan' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    delete $record->{authority};
    write_json($path, $record);
    write_file("$path.sha256", sha_file($path) . "  cpan-acceptance.json\n");
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'CPAN evidence without execution authority rejects');
    like($log, qr/CPAN execution authority is missing/,
        'final assembly requires the trusted execution bridge');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'cpan' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    my ($raw) = grep { $_->{kind} eq 'raw-log' } @{$record->{artifacts}};
    append_file(File::Spec->catfile($fixture->{directory}, $raw->{path}),
        "tampered after producer seal\n");
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'tampered nested CPAN raw artifact rejects');
    like($log, qr/CPAN artifact .* hash mismatch/,
        'nested producer evidence is independently immutable and hash-checked');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'make' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{producer} = 'redirected_make_evidence.pl';
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'wrong schema-locked make producer label rejects');
    like($log, qr/Make producer schema or mode is wrong/,
        'make producer metadata cannot redirect the trusted lane selector');
}

{
    my $fixture = fixture();
    my $sync = $fixture->{authority}{prerequisites}{perl5_sync};
    my $path = File::Spec->catfile($fixture->{directory}, $sync->{artifact}{path});
    my $record = read_json($path);
    delete $record->{tools}{patch};
    write_json($path, $record);
    $sync->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'sync evidence missing patch tool identity rejects');
    like($log, qr/Perl5 sync tools patch identity is missing/,
        'strict sync validator requires all five nested tools');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'make' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{schema} = 'perlonjava.phase36.make-evidence-report/v1';
    $record->{kind} = 'make-report';
    $record->{mode} = 'report';
    $record->{verified} = JSON::PP::false;
    $record->{authoritative} = JSON::PP::false;
    reseal_make_payload($record);
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'successful make report cannot become authority');
    like($log, qr/Make producer schema or mode is wrong/,
        'only the accepted make authority schema is consumed');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'make' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{identity}{jar_sha256} = 'a' x 64;
    $record->{artifacts}{jar}{sha256} = 'a' x 64;
    reseal_make_payload($record);
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'self-consistently resealed make JAR substitution rejects');
    like($log, qr/Producer identity is stale: jar_sha256/,
        'make authority is bound to the CLI-selected package JAR');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'make' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{warning_scan}{count} = 1;
    $record->{warning_scan}{matches} = ['warning'];
    reseal_make_payload($record);
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'resealed nonzero make warning scan rejects');
    like($log, qr/Make warning_scan is incomplete or nonzero/,
        'warning-free make is derived from the retained complete-log scan');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'make' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    my $log_path = $record->{artifacts}{make_log}{path};
    $log_path = File::Spec->catfile($fixture->{directory}, $log_path)
        unless File::Spec->file_name_is_absolute($log_path);
    append_file($log_path, "tampered after authority publication\n");
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'tampered nested make log rejects');
    like($log, qr/make artifact make_log hash mismatch/,
        'make sidecars remain independently hash-bound after publication');
}

{
    my $fixture = fixture();
    my $sync = $fixture->{authority}{prerequisites}{perl5_sync};
    my $path = File::Spec->catfile($fixture->{directory}, $sync->{artifact}{path});
    my $record = read_json($path);
    $record->{command}{complete_log} .= "Verified remote tip: $perl5\n";
    $record->{command}{complete_log_sha256}
        = sha256_hex($record->{command}{complete_log});
    write_json($path, $record);
    $sync->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'sync evidence with duplicate log marker rejects');
    like($log, qr/missing, duplicate, or inconsistent markers/,
        'strict sync validator parses unique markers from retained complete log');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'cpan' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    my $mode = $record->{results}{Moo}{modes}{jvm};
    my $raw_relative = $mode->{raw_log}{path};
    my $raw_path = File::Spec->catfile($fixture->{directory}, $raw_relative);
    append_file($raw_path, "WARNING: resealed but unsafe\n");
    my $raw_sha = sha_file($raw_path);
    $mode->{raw_log}{sha256} = $raw_sha;
    (grep { $_->{path} eq $raw_relative } @{$record->{artifacts}})[0]{sha256}
        = $raw_sha;
    my $meta_relative = File::Spec->catfile('runs', slug('Moo-jvm'), 'result.json');
    my $meta_path = File::Spec->catfile($fixture->{directory}, $meta_relative);
    write_json($meta_path, $mode);
    (grep { $_->{path} eq $meta_relative } @{$record->{artifacts}})[0]{sha256}
        = sha_file($meta_path);
    write_json($path, $record);
    write_file("$path.sha256", sha_file($path) . "  cpan-acceptance.json\n");
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'resealed CPAN raw warning rejects');
    like($log, qr/raw log contains an unapproved warning/,
        'structured empty warning arrays cannot hide raw warning evidence');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'ci' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{raw_api_evidence}[0]{sha256} = sha256_hex('forged raw bytes');
    reseal_payload($record);
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'resealed CI record with forged raw API identity rejects');
    like($log, qr/CI raw API record identity is incomplete/,
        'CI acceptance validates retained raw API bytes rather than a report summary');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'ci' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{evidence}{checks}[0]{check_suite_id} = 901;
    reseal_payload($record);
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'resealed CI record with stale check suite rejects');
    like($log, qr/CI retained check is stale or incomplete/,
        'CI acceptance binds checks to the selected successful workflow run');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'ci' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{evidence}{fixture_only} = JSON::PP::true;
    reseal_payload($record);
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'offline fixture-only CI authority rejects after reseal');
    like($log, qr/CI retained evidence is not live exact-source evidence/,
        'CI acceptance cannot promote producer test fixtures');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'ci' } @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{producer} = 'redirected_ci_evidence.pl';
    reseal_payload($record);
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'wrong schema-locked CI producer label rejects');
    like($log, qr/CI producer schema or kind is wrong/,
        'CI producer metadata cannot redirect the trusted lane selector');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'packaging' }
        @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{producer} = 'redirected_package_evidence.pl';
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'wrong schema-locked package producer label rejects');
    like($log, qr/Packaging producer label is wrong/,
        'package metadata cannot redirect the trusted lane selector');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'packaging' }
        @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{kind} = 'phase36-package-evidence-report';
    $record->{authoritative} = JSON::PP::false;
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'package compatibility report cannot become authority');
    like($log, qr/packaging producer has unsupported fields: authoritative|Packaging schema_version or kind is wrong/,
        'only the strict package bridge is accepted');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'performance' }
        @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{baseline_seconds} = [1, 1, 1, 1, 1];
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'mixed legacy and final performance authority rejects');
    like($log, qr/final performance producer has unsupported fields: baseline_seconds/,
        'legacy timing cannot survive beside final-wrapper authority');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'performance' }
        @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{decision} = 'review-stop';
    $record->{verified} = JSON::PP::false;
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'non-passing final performance artifact rejects');
    like($log, qr/Final performance did not pass or stopped for review/,
        'only accepted final performance decisions delegate authority');
}

{
    my $fixture = fixture();
    mutate_performance($fixture, sub {
        $_[0]{psycho_speed}{rows} = [];
    }, 1);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'skeletal final-performance workload graph rejects');
    like($log, qr/psycho-speed or ordered evidence is missing/,
        'final authority requires the complete retained workload graph');
}

{
    my $fixture = fixture();
    mutate_performance($fixture, sub {
        $_[0]{identity}{candidate_launcher}{sha256} = sha256_hex('redirected');
    });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'redirected final-performance launcher identity rejects');
    like($log, qr/candidate JAR or launcher identity is stale/,
        'final performance is cross-bound to the trusted launcher bytes');
}

{
    my $fixture = fixture();
    mutate_performance($fixture, sub { $_[0]{verified} = 1 });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'numeric final-performance completion flag rejects');
    like($log, qr/did not pass or stopped for review/,
        'completion authority requires an exact JSON boolean');
}

{
    my $fixture = fixture();
    my $substituted = File::Spec->catfile($fixture->{directory},
        'substituted-requirements.json');
    my $document = JSON::PP->new->decode(read_file($requirements));
    $document->{minimum_performance_samples} = 1;
    write_json($substituted, $document);
    my ($status, $log) = run_assembler($fixture, $substituted);
    isnt($status, 0, 'self-hashed legacy requirements substitution rejects');
    like($log, qr/requirements has unsupported fields: minimum_performance_samples/,
        'trusted requirements schema cannot be redirected to legacy policy');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'packaging' }
        @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory}, $lane->{artifact}{path});
    my $record = read_json($path);
    $record->{artifacts}{notice_license} = $record->{artifacts}{report};
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'separate notice gate not retained by strict package rejects');
    like($log, qr/Notice\/license gate is not the strict package-retained artifact/,
        'package and notice gates share one immutable retained record');
}

{
    my $fixture = fixture();
    mutate_regex_result($fixture, 'interpreter', sub {
        $_[0]{'test-1.t'}{ok_count} = 0;
        $_[0]{'test-1.t'}{not_ok_count} = 1;
    });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'strict regex backend count difference rejects');
    like($log, qr/Strict regex backend parity differs: test-1\.t/,
        'complete strict result-map parity compares assertion counts');
}

{
    my $fixture = fixture();
    mutate_regex_result($fixture, 'interpreter', sub {
        $_[0]{'test-2.t'}{errors} = ['backend-only diagnostic'];
    });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'strict regex backend diagnostic difference rejects');
    like($log, qr/Strict regex backend parity differs: test-2\.t/,
        'unapproved strict diagnostic differences cannot be hidden');
}

{
    my $fixture = fixture();
    mutate_regex_result($fixture, 'interpreter', sub {
        $_[0]{'outside-strict-scope.t'} = delete $_[0]{'test-3.t'};
        $_[0]{'outside-strict-scope.t'}{file} = 'outside-strict-scope.t';
    });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'missing strict regex backend row rejects');
    like($log, qr/Strict regex backend parity is missing interpreter row: test-3\.t/,
        'strict inventory and both complete result maps are joined exactly');
}

{
    my $fixture = fixture();
    mutate_regex_result($fixture, 'interpreter', sub {
        $_[0]{'test-7.t'}{errors} = ['outside strict semantic inventory'];
    });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0,
        'unclassified invalid row outside strict inventory is rejected');
    like($log, qr/broad invalid row is not classified as inherited/,
        'strict parity scope does not weaken exact broad classification');
}

{
    my $fixture = fixture();
    mutate_regex_manifest($fixture, sub {
        $_[0]{release_authority}{package_evidence}{sha256} = 'a' x 64;
    });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'regex release authority with substituted package rejects');
    like($log, qr/Regex package release authority selected different evidence/,
        'corpus authority binds the exact package gate artifact');
}

{
    my $fixture = fixture();
    mutate_regex_manifest($fixture, sub {
        $_[0]{release_authority}{authoritative} = JSON::PP::false;
        $_[0]{release_authority}{mode} = 'prepare-only';
    });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'prepare-only corpus release authority rejects');
    like($log, qr/Regex release authority is not authoritative acceptance evidence/,
        'only the accepted corpus authority mode can reach the envelope');
}

{
    my $fixture = fixture();
    mutate_regex_manifest($fixture, sub {
        $_[0]{identity}{runner_policy}{cpu_heavy_jobs} = 4;
    });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'unratified corpus CPU-heavy concurrency rejects');
    like($log, qr/Regex producer runner policy is malformed/,
        'corpus authority explicitly binds a CPU-heavy budget at most three');
}

{
    my $fixture = fixture();
    mutate_regex_manifest($fixture, sub {
        $_[0]{release_authority}{make_evidence}{identity}
            {jar_embedded_commit} = 'a' x 40;
    });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'corpus authority with wrong embedded JAR commit rejects');
    like($log, qr/Regex make release jar_embedded_commit differs from selected source/,
        'corpus launch binds runtime and embedded commits to selected source');
}

{
    my $fixture = fixture();
    mutate_regex_result($fixture, 'jvm', sub {
        $_[0]{'test-1.t'}{file} = 'other.t';
    });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'runner row with mismatched real file field rejects');
    like($log, qr/jvm runner row file identity differs: test-1\.t/,
        'real runner file bookkeeping is key-bound');
}

{
    my $fixture = fixture();
    mutate_regex_result($fixture, 'jvm', sub {
        $_[0]{'test-1.t'}{duration} = '-1';
    });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'malformed real runner duration rejects');
    like($log, qr/jvm runner row duration is malformed: test-1\.t/,
        'runner timing bookkeeping is bounded without entering semantic parity');
}

{
    my $fixture = fixture();
    for my $backend (qw(jvm interpreter)) {
        mutate_regex_result($fixture, $backend, sub {
            my $row = $_[0]{'test-7.t'};
            @$row{qw(status ok_count total_tests planned_tests actual_tests_run
                exit_code)} = ('error', 0, 0, 0, 0, 1);
            $row->{errors} = ['inherited broad failure'];
        });
        mutate_regex_artifact($fixture, "$backend-comparison.json", sub {
            $_[0]{inherited_invalid} = [{file => 'test-7.t'}];
        });
    }
    my ($status, $log) = run_assembler($fixture);
    is($status, 0, 'classified inherited-invalid row outside strict inventory is admissible')
        or diag $log;
}

{
    my $fixture = fixture();
    for my $backend (qw(jvm interpreter)) {
        mutate_regex_result($fixture, $backend, sub {
            my $row = $_[0]{'test-1.t'};
            @$row{qw(status ok_count total_tests planned_tests actual_tests_run
                exit_code)} = ('error', 0, 0, 0, 0, 1);
            $row->{errors} = ['strict failure'];
        });
    }
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'invalid row inside strict inventory rejects');
    like($log, qr/jvm strict runner row is incomplete, timed out, or zero-TAP: test-1\.t/,
        'strict success policy is applied only to exact strict inventory');
}

{
    my $fixture = fixture();
    mutate_regex_manifest($fixture, sub {
        delete $_[0]{artifacts}{'jvm-strict-regex-comparison.json'};
    });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'missing strict comparison artifact rejects');
    like($log, qr/Regex producer artifact is missing: jvm-strict-regex-comparison\.json/,
        'both producer strict comparisons are mandatory');
}

{
    my $fixture = fixture();
    my ($lane) = grep { $_->{gate} eq 'ledger' } @{$fixture->{authority}{lanes}};
    my $manifest = read_json(File::Spec->catfile($fixture->{directory},
        $lane->{artifact}{path}));
    my $descriptor = $manifest->{artifacts}{'jvm-strict-regex-comparison.json'};
    append_file(File::Spec->catfile($fixture->{directory}, $descriptor->{path}),
        "tampered strict comparison\n");
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'tampered strict comparison artifact rejects');
    like($log, qr/regex producer artifact jvm-strict-regex-comparison\.json hash mismatch/,
        'strict comparison bytes are independently hash-bound');
}

for my $case (
    [algorithm => sub { $_[0] =~ s/\ASHA-256/SHA-512/ }],
    [payload => sub { $_[0] =~ s/\A(SHA-256 )([0-9a-f]{64})/$1 . ('a' x 64)/e }],
    [json => sub { $_[0] =~ s/([0-9a-f]{64})\n\z/('b' x 64) . "\n"/e }],
    [newline => sub { chomp $_[0] }],
    [bytes => sub { $_[0] .= 'garbage' }],
) {
    my ($label, $mutator) = @$case;
    my $fixture = fixture();
    mutate_make_external_seal($fixture, $mutator);
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, "make external seal $label corruption rejects");
    like($log, qr/Regex make release seal content is invalid/,
        "make external seal $label is semantically authenticated");
}

{
    my $fixture = fixture();
    mutate_regex_manifest($fixture, sub {
        my $authority = $_[0]{release_authority}{make_evidence};
        my $alternate = File::Spec->catfile($fixture->{directory},
            'alternate-make.seal');
        write_file($alternate, read_file($authority->{seal}{path}));
        $authority->{seal} = {path => $alternate, sha256 => sha_file($alternate)};
    });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, 'make external seal path substitution rejects');
    like($log, qr/Regex make release seal path differs from selected make evidence/,
        'external seal path is derived from selected make authority path');
}

for my $case (
    [source_root => sub { $_[0]{release_authority}{selected}{source_root}
        = $_[1]{directory} }],
    [jar => sub { $_[0]{release_authority}{selected}{jar}{path}
        = File::Spec->catfile($_[1]{directory}, 'package-retained.jar') }],
    [sbom => sub { $_[0]{release_authority}{selected}{sbom}{path}
        = File::Spec->catfile($_[1]{directory}, 'package-sbom.json') }],
    [baseline => sub {
        my $alternate = File::Spec->catfile($_[1]{directory}, 'alternate-baseline.log');
        write_file($alternate, "alternate baseline\n");
        $_[0]{release_authority}{selected}{baseline}{path} = $alternate;
    }],
) {
    my ($label, $mutator) = @$case;
    my $fixture = fixture();
    mutate_regex_manifest($fixture, sub { $mutator->($_[0], $fixture) });
    my ($status, $log) = run_assembler($fixture);
    isnt($status, 0, "selected release $label path substitution rejects");
    like($log, qr/Regex selected .* differs/,
        "selected release $label path is cross-bound");
}

{
    my $fixture = fixture();
    my ($status, $log) = run_assembler_with_prepublication_mutation($fixture);
    isnt($status, 0, 'post-validation nested evidence mutation rejects');
    like($log, qr/changed before envelope publication/,
        'all consumed inputs are revalidated at the publication boundary');
    ok(!-e $fixture->{output}, 'prepublication mutation publishes no envelope');
}

done_testing;

sub fixture {
    my $directory = abs_path(tempdir(CLEANUP => 1));
    my $source_directory = abs_path(tempdir(CLEANUP => 1));
    my $tool_directory = abs_path(tempdir(CLEANUP => 1));
    write_file(File::Spec->catfile($directory, 'baseline.log'), "baseline\n");
    write_file(File::Spec->catfile($directory, 'sbom.json'), "{}\n");
    write_file(File::Spec->catfile($directory, 'jperl'), "jperl\n");
    write_file(File::Spec->catfile($source_directory, 'candidate.jar'), "jar\n");
    my %paths;

    my $ledger = {
        schema_version => 1,
        policy => 'current latest upstream perl5 checkout; no pinned revision',
        scope => 'complete',
        summary => {core_re_files => 1, auxiliary_regex_files => 0,
            runner_files => 7, documented_unit_gates => 0,
            direct_thread_pairs => 1, thread_only_tests => 0,
            unresolved_references => 0},
        core_re_files => ['test-1.t'], auxiliary_regex_files => [],
        runner_files => [map { "test-$_.t" } 1 .. 7],
        documented_unit_gates => [],
        direct_thread_pairs => [{direct => 'test-2.t', thread => 'test-3.t'}],
        thread_only_tests => [], unresolved_references => [],
    };
    $paths{ledger} = write_named_json($directory, 'regex-ledger.json', $ledger);
    $paths{'strict-regex-ledger'} = write_named_json($directory,
        'strict-regex-ledger.json', $ledger);
    my @strict_files = map { "test-$_.t" } 1 .. 3;
    write_file(File::Spec->catfile($directory, 'strict-regex-files.txt'),
        join('', map { "$_\n" } @strict_files));
    my $strict_digest = sha256_hex(join('', map { "$_\n" } @strict_files));
    my @raw_tap_entries;
    for my $backend (qw(jvm interpreter)) {
        my %runner_rows = map { my $file = "test-$_.t";
            my $relative = join('/', 'raw-tap', $backend,
                sha256_hex("$backend\0$file") . '.tap');
            my $raw = File::Spec->catfile($directory,
                File::Spec->splitdir($relative));
            make_path((File::Spec->splitpath($raw))[1]);
            write_file($raw, "1..1\nok 1 - $backend $file\n");
            push @raw_tap_entries, {backend => $backend, file => $file,
                path => $relative, size => -s $raw, sha256 => sha_file($raw)};
            ($file => {
            file => $file, duration => 0.1, raw_output_path => $relative,
            status => 'pass', total_tests => 1, exit_code => 0,
            ok_count => 1, not_ok_count => 0, incomplete_tests => 0,
            skip_count => 0, todo_count => 0, errors => [], missing_features => [],
            timeout => JSON::PP::false, truncated => JSON::PP::false,
            execution_error => JSON::PP::false,
            planned_tests => 1, actual_tests_run => 1,
        }) } 1 .. 7;
        $paths{"$backend-results"} = write_named_json($directory,
            "$backend-results.json", {timestamp => 'synthetic',
                jperl_path => File::Spec->catfile($directory, 'jperl'),
                summary => {}, feature_impact => {},
                results => \%runner_rows});
        $paths{"$backend-comparison"} = write_named_json($directory,
            "$backend-comparison.json", {
                expected_files => 7,
                summary => {baseline_ok => 7, candidate_ok => 7, delta_ok => 0,
                    baseline_total => 7, candidate_total => 7, delta_total => 0,
                    baseline_files => 7, candidate_files => 7},
                regressions => [], missing_files => [], zero_tap => [],
                truncated => [], execution_issues => [], new_invalid => [],
                improvements => [], plan_changes => [], added_files => [],
                inherited_invalid => [],
            });
        $paths{"$backend-strict-regex-comparison"} = write_named_json($directory,
            "$backend-strict-regex-comparison.json", {
                expected_files => 3,
                compared_files => \@strict_files,
                compared_files_sha256 => $strict_digest,
                summary => {baseline_ok => 3, candidate_ok => 3, delta_ok => 0,
                    baseline_total => 3, candidate_total => 3, delta_total => 0,
                    baseline_files => 3, candidate_files => 3},
                regressions => [], missing_files => [], zero_tap => [],
                truncated => [], execution_issues => [], new_invalid => [],
                improvements => [], plan_changes => [], added_files => [],
                inherited_invalid => [],
            });
        write_file(File::Spec->catfile($directory,
            "$backend-strict-regex-comparison.log"),
            "Compared file identity: files=3 sha256=$strict_digest\n");
    }
    my $raw_aggregate = 0;
    $raw_aggregate += $_->{size} for @raw_tap_entries;
    write_named_json($directory, 'raw-tap-index.json', {
        schema_version => 1, kind => 'phase36-regex-raw-tap-index',
        mapping => 'sha256-backend-nul-normalized-relative-file/v1',
        aggregate_bytes => $raw_aggregate, entries => \@raw_tap_entries,
    });
    write_file(File::Spec->catfile($directory, 'packaging.log'), "strict packaging passed\n");
    write_file(File::Spec->catfile($directory, 'regex-jperl-version.log'),
        "This is PerlOnJava ($source)\n");
    $paths{packaging} = 'packaging.log';
    my %regex_artifacts = map {
        my $path = File::Spec->catfile($directory, $paths{$_});
        my $name = $_ eq 'ledger' ? 'regex-ledger.json'
            : $_ eq 'packaging' ? 'packaging.log' : "$_.json";
        $name => { path => $paths{$_}, sha256 => sha_file($path) }
    } qw(ledger jvm-results interpreter-results jvm-comparison
        interpreter-comparison strict-regex-ledger
        jvm-strict-regex-comparison interpreter-strict-regex-comparison);
    $regex_artifacts{'jperl-version.log'} = {path => 'regex-jperl-version.log',
        sha256 => sha_file(File::Spec->catfile($directory,
            'regex-jperl-version.log'))};
    for my $name (qw(strict-regex-files.txt jvm-strict-regex-comparison.log
            interpreter-strict-regex-comparison.log raw-tap-index.json)) {
        $regex_artifacts{$name} = {path => $name,
            sha256 => sha_file(File::Spec->catfile($directory, $name))};
    }
    my $regex = {
        schema_version => 1, mode => 'acceptance',
        source => { starting_sha => $source, final_sha => $source,
            perl5_sha_as_provenance => $perl5,
            tracked_state_signature => sha256_hex('') },
        identity => { source_commit => $source, runner_commit => $source,
            perl5_commit => $perl5,
            launcher => { path => File::Spec->catfile($directory, 'jperl'),
                sha256 => $jperl },
            jar => { path => File::Spec->catfile($directory,
                    'candidate.jar'), sha256 => $jar },
            sbom => { path => File::Spec->catfile($directory,
                    'sbom.json'), sha256 => $sbom },
            baseline => { path => File::Spec->catfile($directory,
                    'baseline.log'), sha256 => $baseline },
            runner_policy => {timeout => 1800, jobs => 10,
                cpu_heavy_jobs => 2} },
        expected_files => 7,
        strict_regex_expected_files => 3, verified_runner_sha => $source,
        ledger_summary => $ledger->{summary},
        strict_regex_ledger_summary => $ledger->{summary},
        baseline => File::Spec->catfile($directory, 'baseline.log'),
        artifact_directory => $directory,
        exit_statuses => { ledger => 0, 'jvm-comparison' => 0,
            'interpreter-comparison' => 0, packaging => 0 },
        artifacts => \%regex_artifacts,
    };
    my $regex_path = write_named_json($directory, 'regex-acceptance.json', $regex);

    my $direct_path = write_named_json($directory, 'direct-thread.json', {
        schema_version => 1, kind => 'direct-thread', verified => JSON::PP::true,
        identity => { source_commit => $source, runner_commit => $source,
            jperl_sha256 => $jperl },
        observations => {description_differences => []},
        details => { expected_pairs => 1, actual_pairs => 1,
            expected_modes => 4, actual_modes => 4,
            expected_thread_only => 0, actual_thread_only => 0,
            expected_thread_only_modes => 2, actual_thread_only_modes => 2,
            mismatches => 0, missing => 0, zero_tap => 0, timeouts => 0,
            truncated => 0, execution_issues => 0,
            assertion_status_mismatches => 0, description_differences => 0,
            classified_shared_failures => 0, unclassified_shared_failures => 0,
            standalone_failures => 0, unused_allowlist => 0,
            status_counts => {}, rows => [], supplemental_core_artifacts => []},
        failures => {map { $_ => [] } qw(missing mismatches zero_tap timeouts
            truncated execution_issues classified_shared_failures
            unclassified_shared_failures standalone_failures unused_allowlist)},
    });

    my $jcpan_hash = sha256_hex('jcpan');
    my $manifest_hash = sha256_hex('manifest');
    my %cpan_authority_hash = map { $_ => sha256_hex("cpan-authority-$_") }
        qw(tuple marker bridge launch seal);
    my %cpan_inputs = (
        source => {path => '/source', commit => $source},
        perl5 => {path => '/perl5', commit => $perl5},
        jperl => {path => '/jperl', sha256 => $jperl},
        jcpan => {path => '/jcpan', sha256 => $jcpan_hash},
        jar => {path => '/candidate.jar', sha256 => $jar},
        sbom => {path => '/sbom.json', sha256 => $sbom},
    );
    my (@cpan_artifacts, %cpan_results);
    write_file(File::Spec->catfile($directory, 'jperl-version.log'), "jperl version\n");
    push @cpan_artifacts, {path => 'jperl-version.log', kind => 'jperl-version',
        sha256 => sha_file(File::Spec->catfile($directory, 'jperl-version.log'))};
    for my $target (@targets) {
        my %modes;
        for my $mode (qw(jvm interpreter)) {
            my $base = File::Spec->catdir('runs', slug("$target-$mode"));
            make_path(File::Spec->catdir($directory, $base));
            my $raw_relative = File::Spec->catfile($base, 'raw.log');
            my $meta_relative = File::Spec->catfile($base, 'result.json');
            my $raw_path = File::Spec->catfile($directory, $raw_relative);
            write_file($raw_path, "ok 1 - synthetic\nFiles=1, Tests=1, 0 wallclock secs\n");
            my %environment = (PERLONJAVA_JAR => '/candidate.jar',
                PERLONJAVA_HOME => '/tmp/home', HOME => '/tmp/home',
                TMPDIR => '/tmp/work', PERL_MM_USE_DEFAULT => '1',
                JPERL_INTERPRETER => $mode eq 'interpreter' ? '1' : undef,
                JPERL_UNIMPLEMENTED => undef, PHASE36_CPAN_TARGET => $target,
                PHASE36_CPAN_MODE => $mode);
            my $mode_result = {target => $target, mode => $mode,
                status => 'pass', total_tests => 1, exit_code => 0, signal => 0,
                timeout => JSON::PP::false, execution_error => JSON::PP::false,
                zero_tap => JSON::PP::false, malformed => JSON::PP::false,
                truncated => JSON::PP::false, failures => 0, skips => 0,
                started_at => '2026-08-23T00:00:00Z',
                ended_at => '2026-08-23T00:00:01Z', duration_seconds => 1,
                unapproved_warnings => [], warning_diagnostics => [],
                argv => ['/jcpan', '-t', $target], environment => \%environment,
                environment_sha256 => sha256_hex(
                    JSON::PP->new->canonical->encode(\%environment)),
                raw_log => {path => $raw_relative, sha256 => sha_file($raw_path)},
                identity => { source_commit => $source, runner_commit => $source,
                    perl5_commit => $perl5, jperl_sha256 => $jperl,
                    jar_sha256 => $jar, sbom_sha256 => $sbom,
                    jar_path => '/candidate.jar', sbom_path => '/sbom.json'} };
            $modes{$mode} = $mode_result;
            write_json(File::Spec->catfile($directory, $meta_relative), $mode_result);
            push @cpan_artifacts,
                {path => $raw_relative, kind => 'raw-log', sha256 => sha_file($raw_path)},
                {path => $meta_relative, kind => 'mode-result', sha256 => sha_file(
                    File::Spec->catfile($directory, $meta_relative))};
        }
        $cpan_results{$target} = { status => 'pass', total_tests => 2,
            timeout => JSON::PP::false, truncated => JSON::PP::false,
            execution_error => JSON::PP::false, rationale => 'sealed target',
            focused_selector_permitted => JSON::PP::false, modes => \%modes };
    }
    my $cpan_path = write_named_json($directory, 'cpan-acceptance.json', {
        schema_version => 2, mode => 'acceptance', status => 'pass',
        identity => { source_commit => $source, runner_commit => $source,
            perl5_commit => $perl5, jperl_sha256 => $jperl, jar_sha256 => $jar,
            sbom_sha256 => $sbom, policy_sha256 => $policy,
            manifest_sha256 => $manifest_hash, jcpan_sha256 => $jcpan_hash,
            authority_tuple_sha256 => $cpan_authority_hash{tuple},
            execution_authorized => JSON::PP::true,
            authority_marker_sha256 => $cpan_authority_hash{marker},
            authority_bridge_sha256 => $cpan_authority_hash{bridge},
            authority_launch_sha256 => $cpan_authority_hash{launch},
            authority_seal_sha256 => $cpan_authority_hash{seal},
            inputs => \%cpan_inputs },
        authority => {
            schema => 'perlonjava.phase36.cpan-launch-authority/v1',
            execution_authorized => JSON::PP::true,
            tuple_sha256 => $cpan_authority_hash{tuple},
            marker_sha256 => $cpan_authority_hash{marker},
            bridge_sha256 => $cpan_authority_hash{bridge},
            launch_sha256 => $cpan_authority_hash{launch},
            seal_sha256 => $cpan_authority_hash{seal},
        },
        expected_targets => [@targets], results => \%cpan_results,
        total_tests => 16, excluded_audits => [], artifacts => \@cpan_artifacts,
    });
    write_file(File::Spec->catfile($directory, 'cpan-acceptance.json.sha256'),
        sha_file(File::Spec->catfile($directory, $cpan_path))
            . "  cpan-acceptance.json\n");

    write_file(File::Spec->catfile($directory, 'candidate.jar'), "jar\n");
    my $ordinary_performance_path = write_named_json($directory,
        'ordinary-performance.json', {
        schema_version => 1, kind => 'performance', verified => JSON::PP::true,
        alternating_order => JSON::PP::true,
        baseline_seconds => [(2) x 5], candidate_seconds => [(1) x 5],
        execution_order => [map { ('baseline', 'candidate') } 1 .. 5],
        source => { candidate => { commit => $source } },
        artifacts => { candidate_jar => {
            path => 'candidate.jar', sha256 => $jar } },
    });
    my $performance_policy = sha256_hex(
        JSON::PP->new->utf8->canonical->encode(
            $requirements_document->{performance_acceptance}));
    my %performance_identity;
    my @performance_identity_files = qw(benchmark jfc jdk_executable
        jdk_version_log ordinary_performance_producer performance_evaluator
        perl_interpreter execution_environment baseline_jar
        interpreter_launcher jfr_tool jfr_metrics_producer time_executable
        git_executable ps_executable uptime_executable ordered_test_source
        ordered_fixture_manifest ordered_fixture_tree_manifest dbix_archive);
    for my $name (@performance_identity_files) {
        my $path = "performance-identity-$name.dat";
        write_file(File::Spec->catfile($directory, $path), "$name\n");
        $performance_identity{$name} = {
            path => $path,
            sha256 => sha_file(File::Spec->catfile($directory, $path)),
        };
    }
    $performance_identity{candidate_jar} = {
        path => 'candidate.jar', sha256 => $jar };
    $performance_identity{candidate_launcher} = {
        path => 'jperl', sha256 => $jperl };
    $performance_identity{baseline_launcher} =
        $performance_identity{interpreter_launcher};
    my $performance_row_path = 'performance-row-evidence.dat';
    write_file(File::Spec->catfile($directory, $performance_row_path),
        "retained performance evidence\n");
    my $performance_row_descriptor = {
        path => $performance_row_path,
        sha256 => sha_file(File::Spec->catfile($directory,
            $performance_row_path)),
    };
    my @psycho_rows;
    for my $spec (@{$requirements_document->{performance_acceptance}
            {psycho_speed_rows}}) {
        for my $backend (qw(jvm interpreter)) {
            push @psycho_rows, {
                backend => $backend, test => $spec->{test},
                source_commit => $source, jar_sha256 => $jar,
                launcher_sha256 => $jperl, exit_code => 0,
                timeout => JSON::PP::false, truncated => JSON::PP::false,
                test_source => {%$performance_row_descriptor},
                tap => {%$performance_row_descriptor},
                command => {%$performance_row_descriptor},
            };
        }
    }
    my @ordered_runs;
    for my $side (@{$requirements_document->{performance_acceptance}
            {ordered_execution_order}}) {
        push @ordered_runs, {
            side => $side, exit_code => 0, timeout => JSON::PP::false,
            map { $_ => {%$performance_row_descriptor} } qw(command environment
                process_inventory_before process_inventory_after load_before
                load_after load_admission tap time_raw jfr_recording
                jfr_summary jfr_metrics),
        };
    }
    my $baseline_source = '0' x 40;
    my $performance_document = {
        schema_version => 1, kind => 'phase36-final-performance',
        verified => JSON::PP::true, decision => 'passed', review_explanations => [],
        identity => { baseline_source_commit => $baseline_source,
            candidate_source_commit => $source,
            candidate_parent_commit => $baseline_source, perl5_commit => $perl5,
            %performance_identity },
        ordinary => { artifact => { path => $ordinary_performance_path,
            sha256 => sha_file(File::Spec->catfile($directory,
                $ordinary_performance_path)) } },
        psycho_speed => {rows => \@psycho_rows}, ordered => {runs => \@ordered_runs},
        policy_sha256 => $performance_policy,
        evaluation => {schema_version => 1, decision => 'passed',
            verified => JSON::PP::true, policy_sha256 => $performance_policy,
            issues => [], review_stops => [], metrics => {}},
    };
    my $performance_contract = sha256_hex(
        JSON::PP->new->utf8->canonical->encode({map {
            $_ => $performance_document->{$_}
        } qw(schema_version kind identity ordinary psycho_speed ordered
            review_explanations)}));
    $performance_document->{authority} = {
        schema_version => 1, kind => 'phase36-performance-authority',
        complete => JSON::PP::true, execution_attested => JSON::PP::true,
        nonce => sha256_hex('nonce'), source => 'synthetic fixture',
        process_tree_contract => 'unix-process-groups-v1',
        evidence_contract_sha256 => $performance_contract,
        hmac_sha256 => sha256_hex('hmac'),
        map { $_ => sha256_hex("performance-authority-$_") } qw(
            authority_key_sha256 orchestrator_sha256
            ordinary_performance_producer_sha256 performance_evaluator_sha256
            benchmark_sha256 perl_interpreter_sha256
            jfr_metrics_producer_sha256 requirements_sha256
            git_executable_sha256 ps_executable_sha256
            uptime_executable_sha256),
    };
    my $performance_path = write_named_json($directory,
        'final-performance.json', $performance_document);
    my $notice_path = write_named_json($directory, 'notice-license.json', {
        schema_version => 1, kind => 'notice-license', verified => JSON::PP::true,
        jar_sha256 => $jar, sbom_sha256 => $sbom,
        missing_notices => 0, changed_notices => 0,
        missing_licenses => 0, changed_licenses => 0,
    });
    my %package_file = (jar => 'candidate.jar', sbom => 'sbom.json',
        deb => 'package.deb', java_bom => 'package-java-bom.json',
        perl_bom => 'package-perl-bom.json', report => 'package-report.json');
    write_file(File::Spec->catfile($directory, $package_file{jar}), "jar\n");
    write_file(File::Spec->catfile($directory, $package_file{sbom}), "{}\n");
    for my $name (qw(deb java_bom perl_bom report)) {
        write_file(File::Spec->catfile($directory, $package_file{$name}),
            "$name\n");
    }
    my %package_descriptor = map { my $file = $package_file{$_};
        my $path = File::Spec->catfile($directory, $file);
        $_ => {path => $file, sha256 => sha_file($path), size => -s $path}
    } keys %package_file;
    my $package_path = write_named_json($directory, 'package.json', {
        schema_version => 1, kind => 'packaging',
        producer => 'run_phase36_package_evidence.pl',
        verified => JSON::PP::true,
        identity => {source_commit => $source, jar_sha256 => $jar,
            sbom_sha256 => $sbom},
        completion => {exit_code => 0, signal => 0, timeout => JSON::PP::false,
            incomplete => JSON::PP::false, review_stop => JSON::PP::false},
        artifacts => {
            report => $package_descriptor{report},
            deliverables => {map { $_ => $package_descriptor{$_} }
                qw(jar sbom deb)},
            sbom_inputs => {map { $_ => $package_descriptor{$_} }
                qw(java_bom perl_bom)},
            logs => {packaging => {path => 'packaging.log',
                sha256 => sha_file(File::Spec->catfile($directory, 'packaging.log')),
                size => -s File::Spec->catfile($directory, 'packaging.log')}},
            notice_license => {path => $notice_path,
                sha256 => sha_file(File::Spec->catfile($directory, $notice_path)),
                size => -s File::Spec->catfile($directory, $notice_path)}},
        missing_entries => 0, duplicate_entries => 0,
    });
    my $completion = { exit_code => 0, signal => 0,
        timeout => JSON::PP::false, incomplete => JSON::PP::false,
        review_stop => JSON::PP::false };
    write_file(File::Spec->catfile($directory, 'make.log'), "make passed\n");
    write_file(File::Spec->catfile($directory, 'ci.log'), "ci passed\n");
    my %make_artifact_name = (jar => 'candidate.jar', make_log => 'make.log',
        jar_embedded => 'make-jar-embedded.json', jar_version => 'make-jar-version.log',
        source_after => 'make-source-after.json',
        source_before => 'make-source-before.json',
        tool_versions => 'make-tool-versions.json');
    for my $name (values %make_artifact_name) {
        my $path = File::Spec->catfile($directory, $name);
        write_file($path, "$name\n") unless -e $path;
    }
    my %make_artifacts = map { my $name = $make_artifact_name{$_};
        my $path = $_ eq 'jar'
            ? File::Spec->catfile($directory, 'candidate.jar')
            : File::Spec->catfile($directory, $name);
        $_ => {path => $path, sha256 => sha_file($path), size => -s $path}
    } keys %make_artifact_name;
    my (%make_tools, %make_inputs);
    for my $name (qw(git jar_tool java make perl shell producer)) {
        my $file = "make-tool-$name";
        my $path = File::Spec->catfile($tool_directory, $file);
        write_file($path, "$name\n");
        my $descriptor = {path => $path, sha256 => sha_file($path), size => -s $path};
        $make_tools{$name} = $name eq 'producer' ? $descriptor
            : {%$descriptor, version_sha256 => sha256_hex("$name version\n")};
    }
    for my $name (qw(build_gradle gradle_wrapper_jar gradle_wrapper_properties
            gradlew makefile settings_gradle)) {
        my $file = "make-input-$name";
        my $path = File::Spec->catfile($source_directory, $file);
        write_file($path, "$name\n");
        $make_inputs{$name} = {path => $path, sha256 => sha_file($path), size => -s $path};
    }
    my $source_extras = {authority_inputs => [], generated_file_count => 0,
        generated_paths => [], generated_total_bytes => 0};
    my $source_state = {all_status_sha256 => sha256_hex('all-status'),
        diff_sha256 => sha256_hex('diff'), extras => $source_extras, head => $source,
        status_sha256 => sha256_hex('status'), tracked_clean => JSON::PP::true};
    my $make_document = {
        schema => 'perlonjava.phase36.make-evidence/v1', schema_version => 1,
        kind => 'make', producer => 'run_phase36_make_evidence.pl',
        mode => 'acceptance', status => 'pass', verified => JSON::PP::true,
        authoritative => JSON::PP::true,
        identity => {source_commit => $source, runner_commit => $source,
            jar_sha256 => $jar, jar_reported_commit => $source,
            jar_embedded_commit => $source},
        source => {root => $source_directory, before => $source_state,
            after => {%$source_state}},
        command => {cwd => $source_directory, argv => ['make'], environment => {},
            started_utc => '2026-08-23T08:00:00Z',
            finished_utc => '2026-08-23T08:01:00Z', duration_milliseconds => 1},
        tools => \%make_tools, inputs => \%make_inputs,
        completion => {%$completion, truncated => JSON::PP::false},
        warning_scan => {classifier => 'phase36-v1',
            classifier_sha256 => sha256_hex('classifier'),
            complete_log_sha256 => $make_artifacts{make_log}{sha256},
            count => 0, matches => []},
        failure_scan => {classifier => 'phase36-v1',
            classifier_sha256 => sha256_hex('classifier'),
            complete_log_sha256 => $make_artifacts{make_log}{sha256},
            count => 0, matches => []},
        artifacts => \%make_artifacts,
    };
    $make_document->{seal} = {algorithm => 'SHA-256', payload_sha256 =>
        sha256_hex(JSON::PP->new->utf8->canonical->encode($make_document))};
    my $make_path = write_named_json($directory, 'make.json', $make_document);
    my $make_seal_path = File::Spec->catfile($directory, 'make.json.seal');
    write_file($make_seal_path, "SHA-256 "
        . $make_document->{seal}{payload_sha256} . " "
        . sha_file(File::Spec->catfile($directory, $make_path)) . "\n");
    my $regex_record = read_json(File::Spec->catfile($directory, $regex_path));
    $regex_record->{release_authority} = {
        schema_version => 1, kind => 'phase36-release-authority',
        authoritative => JSON::PP::true, mode => 'acceptance',
        package_evidence => {path => File::Spec->catfile($directory, $package_path),
            sha256 => sha_file(File::Spec->catfile($directory, $package_path)),
            identity => {source_commit => $source, jar_sha256 => $jar,
                sbom_sha256 => $sbom}},
        make_evidence => {path => File::Spec->catfile($directory, $make_path),
            sha256 => sha_file(File::Spec->catfile($directory, $make_path)),
            seal => {path => $make_seal_path, sha256 => sha_file($make_seal_path)},
            identity => {source_commit => $source, runner_commit => $source,
                jar_sha256 => $jar, jar_reported_commit => $source,
                jar_embedded_commit => $source}},
        selected => {source_root => $source_directory, source_commit => $source,
            runner_commit => $source,
            jar => {path => File::Spec->catfile($directory, 'candidate.jar'),
                sha256 => $jar},
            sbom => {path => File::Spec->catfile($directory, 'sbom.json'),
                sha256 => $sbom},
            baseline => {path => File::Spec->catfile($directory, 'baseline.log'),
                sha256 => $baseline}},
    };
    write_json(File::Spec->catfile($directory, $regex_path), $regex_record);
    my %ci_platforms = (
        'ubuntu-latest' => {status => 'success', source_commit => $source,
            job_check_name => 'build (ubuntu-latest)', job_id => 501,
            check_run_id => 501},
        'windows-latest' => {status => 'success', source_commit => $source,
            job_check_name => 'build (windows-latest)', job_id => 502,
            check_run_id => 502},
    );
    my $ci_run = {id => 100, run_number => 1, run_attempt => 1,
        workflow_id => 201274429, check_suite_id => 900, head_sha => $source,
        event => 'push', status => 'completed', conclusion => 'success',
        created_at => '2026-08-23T08:00:00Z',
        updated_at => '2026-08-23T08:05:00Z'};
    my @ci_jobs = map { my $id = $_; my $platform = $id == 501
            ? 'ubuntu-latest' : 'windows-latest'; {
        id => $id, run_id => 100, run_attempt => 1,
        name => "build ($platform)", head_sha => $source,
        status => 'completed', conclusion => 'success',
        started_at => '2026-08-23T08:01:00Z',
        completed_at => '2026-08-23T08:04:00Z'} } (501, 502);
    my @ci_checks = map { my $id = $_; my $platform = $id == 501
            ? 'ubuntu-latest' : 'windows-latest'; {
        id => $id, name => "build ($platform)", head_sha => $source,
        status => 'completed', conclusion => 'success',
        started_at => '2026-08-23T08:01:00Z',
        completed_at => '2026-08-23T08:04:00Z', check_suite_id => 900,
        app => {id => 15368, slug => 'github-actions'}} } (501, 502);
    my @raw_labels = qw(tool:git-version tool:gh-version api:workflow
        api:commit api:runs-1 api:jobs api:checks);
    my @raw_api = map { my $bytes = "$_ evidence\n"; {
        label => $_, size => length($bytes), sha256 => sha256_hex($bytes),
        base64 => encode_base64($bytes, ''),
        ($_ =~ /\Aapi:/ ? (endpoint => "repos/fixture/$_") : ())} } @raw_labels;
    my $ci_document = {
        schema => 'perlonjava.phase36.final-envelope-bridge/v1',
        schema_version => 1, kind => 'ci', producer => 'run_phase36_ci_evidence.pl',
        status => 'pass', mode => 'acceptance',
        verified => JSON::PP::true, authoritative => JSON::PP::true,
        identity => {source_commit => $source},
        source => {repository => 'fglock/PerlOnJava', commit => $source},
        completion => {%$completion}, platforms => \%ci_platforms,
        evidence => {
            schema => 'perlonjava.phase36.ci-acceptance-evidence/v1',
            producer_version => '1.1.0', producer_sha256 => sha256_hex('ci-producer'),
            fixture_only => JSON::PP::false, repository => 'fglock/PerlOnJava',
            source_commit => $source, local_clean_exact_commit => JSON::PP::true,
            workflow => {id => 201274429, name => 'Java CI with Gradle',
                path => '.github/workflows/gradle.yml',
                sha256 => sha256_hex('workflow'), size => 10},
            policy => {path => 'dev/tools/phase36_ci_evidence_policy.json',
                sha256 => sha256_hex('ci-policy'),
                requirements_path => 'dev/tools/phase36_acceptance_requirements.json',
                requirements_sha256 => sha_file($requirements)},
            run => $ci_run,
            required_matrix => {'ubuntu-latest' => 'build (ubuntu-latest)',
                'windows-latest' => 'build (windows-latest)'},
            jobs => \@ci_jobs, checks => \@ci_checks,
        },
        tools => {
            git => {path => '/usr/bin/git', sha256 => sha256_hex('git'), size => 3,
                version_sha256 => sha256_hex('git version'), version => 'git 2.0'},
            gh => {path => '/usr/bin/gh', sha256 => sha256_hex('gh'), size => 2,
                version_sha256 => sha256_hex('gh version'), version => 'gh 2.0',
                offline => JSON::PP::false},
        },
        raw_api_evidence => \@raw_api,
    };
    $ci_document->{seal} = {algorithm => 'sha256',
        payload_sha256 => sha256_hex(JSON::PP->new->utf8->canonical->encode(
            $ci_document))};
    my $ci_path = write_named_json($directory, 'ci.json', $ci_document);

    my $sync_log = "Perl upstream commit: $perl5\nVerified remote tip: $perl5\n"
        . "Full manifest: 5 import(s) to process.\n"
        . "Protected paths from config (1):\n  Successful: 5\n  Errors: 0\n"
        . "Running second sync for idempotence verification.\n"
        . "Full manifest: 5 import(s) to process.\n"
        . "Protected paths from config (1):\n  Successful: 5\n  Errors: 0\n"
        . "Idempotence verified: second sync changed no imported outputs.\n";
    my $checkout_source = {path => '/source', commit => $source,
        branch => 'candidate', tracked_clean => JSON::PP::true,
        clean => JSON::PP::true, acceptance_clean => JSON::PP::true,
        untracked_paths => [], allowed_generated_untracked => [],
        unexpected_untracked => [], status_sha256 => sha256_hex('source-status')};
    my $checkout_perl5 = {path => '/perl5', commit => $perl5,
        branch => 'blead', tracked_clean => JSON::PP::true,
        clean => JSON::PP::true, acceptance_clean => JSON::PP::true,
        untracked_paths => [], allowed_generated_untracked => [],
        unexpected_untracked => [], status_sha256 => sha256_hex('perl5-status')};
    my $upstream = {remote => 'origin', repository_url => 'https://example/perl5.git',
        branch => 'blead', upstream => 'origin/blead', tip => $perl5};
    my %sync_tools = map { $_ => {path => "/tools/$_", sha256 => sha256_hex($_)} }
        qw(git make patch perl rsync);
    my %sync_inputs = map { $_ => {path => "/source/$_", sha256 => sha256_hex($_)} }
        qw(config makefile producer sync_script update_script);
    my $name_identity = {path => '/source/Name.pl', sha256 => sha256_hex('Name.pl'),
        present => JSON::PP::true};
    my $sync_path = write_named_json($directory, 'perl5-sync.json', {
        schema_version => 1, kind => 'phase36-perl5-sync-evidence', status => 'pass',
        expected_source_commit => $source, final_source_commit => $source,
        timeout_seconds => 60, repository => 'https://example/perl5.git',
        source => {before => {%$checkout_source}, after => {%$checkout_source}},
        perl5 => {before => {%$checkout_perl5, commit => ('a' x 40)},
            after => {%$checkout_perl5}},
        upstream => {before => {%$upstream}, after => {%$upstream}},
        tools => \%sync_tools, inputs => \%sync_inputs,
        command => {argv => ['/tools/make', '-C', '/source',
                'PERL=/tools/perl', 'perl5-sync-check'],
            environment => {PERL5_REPOSITORY => 'https://example/perl5.git',
                FILTER => undef, PATH => '/tools', LC_ALL => 'C', LANG => 'C'},
            exit_code => 0, signal => 0, timeout => JSON::PP::false,
            duration_seconds => 1, complete_log => $sync_log,
            complete_log_sha256 => sha256_hex($sync_log)},
        sync_markers => {full_manifest_count => 5, pass_count => 2,
            successful_per_pass => [5, 5], errors_per_pass => [0, 0],
            protected_count_per_pass => [1, 1], second_pass_seen => JSON::PP::true,
            idempotence_verified => JSON::PP::true},
        protected_targets => [{path => 'protected.txt', sha256 => sha256_hex('protected')}],
        unicode_name => {before => {imported => {%$name_identity},
            upstream => {%$name_identity}}, after => {imported => {%$name_identity},
            upstream => {%$name_identity}}},
    });

    my %producer = (
        ledger => 'run_phase36_regex_acceptance.pl',
        jvm => 'run_phase36_regex_acceptance.pl',
        interpreter => 'run_phase36_regex_acceptance.pl',
        packaging => 'run_phase36_package_evidence.pl',
        'direct-thread' => 'collect_phase36_direct_thread.pl',
        cpan => 'run_phase36_cpan_acceptance.pl',
        performance => 'run_phase36_final_performance.pl',
        'notice-license' => 'verify_phase36_notice_license.pl',
        make => 'run_phase36_make_evidence.pl', ci => 'run_phase36_ci_evidence.pl',
    );
    my %gate_path = ((map { $_ => $regex_path }
            qw(ledger jvm interpreter)),
        packaging => $package_path,
        'direct-thread' => $direct_path, cpan => $cpan_path,
        performance => $performance_path, 'notice-license' => $notice_path,
        make => $make_path, ci => $ci_path);
    my $authority = {
        schema_version => 1, kind => 'phase36-envelope-authority', mode => 'acceptance',
        identity => { source_commit => $source, runner_commit => $source,
            perl5_commit => $perl5, jperl_sha256 => $jperl, jar_sha256 => $jar,
            sbom_sha256 => $sbom, baseline_sha256 => $baseline },
        prerequisites => { perl5_sync => {
            producer => 'run_phase36_perl5_sync_evidence.pl',
            artifact => {path => $sync_path,
                sha256 => sha_file(File::Spec->catfile($directory, $sync_path))},
        } },
        lanes => [map { my $gate = $_; {
            gate => $gate, producer => $producer{$gate}, artifact => {
                path => $gate_path{$gate},
                sha256 => sha_file(File::Spec->catfile($directory, $gate_path{$gate})),
            } } } @gates],
    };
    my $fixture = { directory => $directory, authority => $authority,
        authority_path => File::Spec->catfile($directory, 'authority.json'),
        output => File::Spec->catfile($directory, 'envelope.json') };
    rewrite_authority($fixture);
    return $fixture;
}

sub rewrite_authority {
    my ($fixture) = @_;
    write_json($fixture->{authority_path}, $fixture->{authority});
}

sub mutate_regex_result {
    my ($fixture, $backend, $mutator) = @_;
    my ($lane) = grep { $_->{gate} eq 'ledger' }
        @{$fixture->{authority}{lanes}};
    my $manifest_path = File::Spec->catfile($fixture->{directory},
        $lane->{artifact}{path});
    my $manifest = read_json($manifest_path);
    my $descriptor = $manifest->{artifacts}{"$backend-results.json"};
    my $result_path = File::Spec->catfile($fixture->{directory},
        $descriptor->{path});
    my $runner = read_json($result_path);
    $mutator->($runner->{results});
    write_json($result_path, $runner);
    $descriptor->{sha256} = sha_file($result_path);
    write_json($manifest_path, $manifest);
    my $manifest_sha = sha_file($manifest_path);
    $_->{artifact}{sha256} = $manifest_sha
        for grep { $_->{producer} eq 'run_phase36_regex_acceptance.pl' }
            @{$fixture->{authority}{lanes}};
    rewrite_authority($fixture);
}

sub mutate_performance {
    my ($fixture, $mutator, $reseal_contract) = @_;
    my ($lane) = grep { $_->{gate} eq 'performance' }
        @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory},
        $lane->{artifact}{path});
    my $record = read_json($path);
    $mutator->($record);
    if ($reseal_contract) {
        $record->{authority}{evidence_contract_sha256} = sha256_hex(
            JSON::PP->new->utf8->canonical->encode({map {
                $_ => $record->{$_}
            } qw(schema_version kind identity ordinary psycho_speed ordered
                review_explanations)}));
    }
    write_json($path, $record);
    $lane->{artifact}{sha256} = sha_file($path);
    rewrite_authority($fixture);
}

sub mutate_regex_manifest {
    my ($fixture, $mutator) = @_;
    my ($lane) = grep { $_->{gate} eq 'ledger' }
        @{$fixture->{authority}{lanes}};
    my $path = File::Spec->catfile($fixture->{directory},
        $lane->{artifact}{path});
    my $record = read_json($path);
    $mutator->($record);
    write_json($path, $record);
    my $sha = sha_file($path);
    $_->{artifact}{sha256} = $sha
        for grep { $_->{producer} eq 'run_phase36_regex_acceptance.pl' }
            @{$fixture->{authority}{lanes}};
    rewrite_authority($fixture);
}

sub mutate_regex_artifact {
    my ($fixture, $name, $mutator) = @_;
    my ($lane) = grep { $_->{gate} eq 'ledger' }
        @{$fixture->{authority}{lanes}};
    my $manifest_path = File::Spec->catfile($fixture->{directory},
        $lane->{artifact}{path});
    my $manifest = read_json($manifest_path);
    my $descriptor = $manifest->{artifacts}{$name};
    my $path = $descriptor->{path};
    $path = File::Spec->catfile($fixture->{directory}, $path)
        unless File::Spec->file_name_is_absolute($path);
    my $record = read_json($path);
    $mutator->($record);
    write_json($path, $record);
    $descriptor->{sha256} = sha_file($path);
    write_json($manifest_path, $manifest);
    my $sha = sha_file($manifest_path);
    $_->{artifact}{sha256} = $sha
        for grep { $_->{producer} eq 'run_phase36_regex_acceptance.pl' }
            @{$fixture->{authority}{lanes}};
    rewrite_authority($fixture);
}

sub mutate_make_external_seal {
    my ($fixture, $mutator) = @_;
    mutate_regex_manifest($fixture, sub {
        my $seal = $_[0]{release_authority}{make_evidence}{seal};
        my $bytes = read_file($seal->{path});
        $mutator->($bytes);
        write_file($seal->{path}, $bytes);
        $seal->{sha256} = sha_file($seal->{path});
    });
}

sub run_assembler_with_prepublication_mutation {
    my ($fixture) = @_;
    local $ENV{HARNESS_ACTIVE} = 1;
    local $ENV{PHASE36_ASSEMBLER_TEST_PREPUBLICATION_BOUNDARY} = 1;
    my $error = gensym;
    my $pid = open3(undef, my $stdout, $error,
        $^X, $tool, assembler_arguments($fixture));
    my $ready = "$fixture->{output}.validation-ready";
    my $continue = "$fixture->{output}.validation-continue";
    for (1 .. 500) {
        last if -f $ready;
        select undef, undef, undef, 0.01;
    }
    die "assembler did not reach prepublication boundary\n" unless -f $ready;
    my ($make_lane) = grep { $_->{gate} eq 'make' }
        @{$fixture->{authority}{lanes}};
    my $make = read_json(File::Spec->catfile($fixture->{directory},
        $make_lane->{artifact}{path}));
    append_file($make->{artifacts}{make_log}{path},
        "mutated after validation\n");
    write_file($continue, "continue\n");
    local $/;
    my $output = (<$stdout> // '') . (<$error> // '');
    waitpid($pid, 0);
    return ($? >> 8, $output);
}

sub reseal_payload {
    my ($record) = @_;
    delete $record->{seal};
    $record->{seal} = {algorithm => 'sha256', payload_sha256 =>
        sha256_hex(JSON::PP->new->utf8->canonical->encode($record))};
}

sub reseal_make_payload {
    my ($record) = @_;
    delete $record->{seal};
    $record->{seal} = {algorithm => 'SHA-256', payload_sha256 =>
        sha256_hex(JSON::PP->new->utf8->canonical->encode($record))};
}

sub run_assembler {
    my ($fixture, $requirements_override) = @_;
    return run_command($^X, $tool,
        assembler_arguments($fixture, $requirements_override));
}

sub assembler_arguments {
    my ($fixture, $requirements_override) = @_;
    my $selected_requirements = $requirements_override // $requirements;
    return ('--authority', $fixture->{authority_path},
        '--requirements', $selected_requirements, '--expected-candidate', $source,
        '--expected-baseline', $baseline, '--expected-perl5', $perl5,
        '--expected-runner', $source, '--expected-jperl-sha256', $jperl,
        '--expected-jar-sha256', $jar, '--expected-sbom-sha256', $sbom,
        '--expected-authority-sha256', sha_file($fixture->{authority_path}),
        '--expected-requirements-sha256', sha_file($selected_requirements),
        '--output', $fixture->{output});
}

sub run_command {
    my (@command) = @_;
    my $error = gensym;
    my $pid = open3(undef, my $stdout, $error, @command);
    local $/;
    my $output = (<$stdout> // '') . (<$error> // '');
    waitpid($pid, 0);
    return ($? >> 8, $output);
}

sub write_named_json {
    my ($directory, $name, $value) = @_;
    write_json(File::Spec->catfile($directory, $name), $value);
    return $name;
}

sub write_json { write_file($_[0], $json->encode($_[1])); }

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!";
}

sub append_file {
    my ($path, $contents) = @_;
    open my $fh, '>>:raw', $path or die "Cannot append $path: $!";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!";
}

sub read_json {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    my $value = JSON::PP->new->decode(do { local $/; <$fh> });
    close $fh or die "Cannot close $path: $!";
    return $value;
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    local $/;
    my $bytes = <$fh>;
    close $fh or die "Cannot close $path: $!";
    return $bytes;
}

sub sha_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot hash $path: $!";
    local $/;
    my $bytes = <$fh>;
    close $fh or die "Cannot close $path: $!";
    return sha256_hex($bytes);
}

sub slug {
    my $value = lc $_[0];
    $value =~ s/[^a-z0-9]+/-/g;
    $value =~ s/^-|-$//g;
    return $value;
}
