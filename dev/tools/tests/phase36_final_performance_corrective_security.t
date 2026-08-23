use strict;
use warnings;

use File::Spec;
use FindBin;
use JSON::PP;
use Test::More;

my $tools = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..'));
my %source = map {
    $_ => read_raw(File::Spec->catfile($tools, $_))
} qw(run_phase36_final_performance.pl run_phase36_regex_performance.pl
    check_phase36_final_performance.pl assemble_phase36_final_performance.pl);
my $module = read_raw(File::Spec->catfile($tools, 'lib', 'PerlOnJava',
    'Phase36PerformanceEvidence.pm'));

for my $tool (qw(git ps uptime)) {
    like($source{'run_phase36_final_performance.pl'}, qr/'\Q$tool\E=s'/,
        "producer requires authority-selected $tool");
    like($source{'check_phase36_final_performance.pl'}, qr/'\Q$tool\E=s'/,
        "checker accepts authority-selected $tool");
}
like($source{'run_phase36_regex_performance.pl'}, qr/'git=s'/,
    'ordinary nested producer requires authority-selected Git');
unlike($source{'run_phase36_final_performance.pl'}, qr/'ordered-test=s'/,
    'ordered test cannot be replaced through the CLI');
like($source{'run_phase36_final_performance.pl'},
    qr/\$launcher, 't\/87ordered\.t'/,
    'ordered argv contains the exact canonical test literal');
like($module, qr/\$argv->\[5\].*eq 't\/87ordered\.t'/s,
    'checker validates the exact ordered argv position and literal');
like($module, qr/\$argv->\[3\].*eq '\/dev\/fd\/3'/s,
    'checker requires the bounded raw-time side channel in exact argv');

for my $text ($source{'run_phase36_final_performance.pl'},
        $source{'run_phase36_regex_performance.pl'}, $module) {
    like($text, qr/kill 'TERM', -\$pid/s,
        'timeout cleanup sends TERM to the full process group');
    like($text, qr/kill 'KILL', -\$pid/s,
        'timeout cleanup sends KILL to the full process group even after TERM');
}
like($source{'run_phase36_final_performance.pl'}, qr/IO::Select.*sysread/s,
    'orchestrator admits child output through a bounded streaming pipe');
like($source{'run_phase36_final_performance.pl'},
    qr/'\/dev\/fd\/3'.*maximum_bytes => 64 \* 1024/s,
    'raw time output is admitted through its own bounded side-output pipe');
like($source{'run_phase36_regex_performance.pl'}, qr/IO::Select.*sysread/s,
    'ordinary producer admits child output through a bounded streaming pipe');
like($module, qr/bounded JFR replay.*IO::Select.*sysread/s,
    'JFR replay output is bounded while it is written');
like($source{'run_phase36_final_performance.pl'}, qr/sub copy_tree.*validate_fixture_tree/s,
    'fixture tree is validated before copying');
like($source{'run_phase36_final_performance.pl'}, qr/fixture symlinks are forbidden/,
    'fixture symlinks are rejected');
like($source{'run_phase36_final_performance.pl'}, qr/exceeds depth 32/,
    'fixture depth is bounded');
like($source{'run_phase36_final_performance.pl'}, qr/exceeds 20000 entries/,
    'fixture entry count is bounded');
like($source{'run_phase36_final_performance.pl'}, qr/file exceeds 256 MiB/,
    'fixture per-file bytes are bounded');
like($source{'run_phase36_final_performance.pl'}, qr/exceeds 2 GiB aggregate/,
    'fixture aggregate bytes are bounded');

like($source{'run_phase36_final_performance.pl'},
    qr/rev-list --parents -n 1 HEAD.*exactly one parent/s,
    'producer rejects root and merge candidates before baseline comparison');
like($module, qr/rev-list --parents -n 1 HEAD.*exactly one parent/s,
    'checker independently rejects root and merge candidates');
like($source{'run_phase36_final_performance.pl'}, qr/PATH => ''/,
    'producer child PATH is closed');
like($module, qr/local %ENV = \(closed_checker_environment/s,
    'checker Git and replay children use a closed environment');
like($module, qr/authority key must have exact mode 0600/,
    'authority key mode is exact on Unix');
like($module, qr/A232 requires a private fixed-location ACL contract/,
    'Windows authority-key policy fails closed with the A232 contract named');
unlike($source{'check_phase36_final_performance.pl'},
    qr/exit 0 if \$mode eq 'report'/,
    'report mode cannot mask a failed or review-stop decision');
like($source{'check_phase36_final_performance.pl'},
    qr/\$mode eq 'strict' && \$decision eq 'passed'/,
    'only strict passed reports can be authoritative');

my $schema = JSON::PP->new->decode(read_raw(File::Spec->catfile($tools,
    'phase36_final_performance_schema.json')));
my $requirements = JSON::PP->new->decode(read_raw(File::Spec->catfile($tools,
    'phase36_acceptance_requirements.json')))->{performance_acceptance};
is($schema->{kind}, 'phase36-final-performance', 'schema kind matches implementation');
is($schema->{authority}{kind}, 'phase36-performance-authority',
    'authority kind matches implementation');
ok(ref($schema->{authority}{source}) eq 'HASH',
    'authority Git state uses the implementation source nesting');
ok(exists($schema->{authority}{evidence_contract_sha256}),
    'schema uses the implemented evidence contract field name');
is_deeply([sort keys %{$schema->{authority}}], [sort qw(schema_version kind
    complete execution_attested nonce authority_key_sha256
    evidence_contract_sha256 hmac_sha256 orchestrator_sha256
    ordinary_performance_producer_sha256 performance_evaluator_sha256
    benchmark_sha256 perl_interpreter_sha256 jfr_metrics_producer_sha256
    requirements_sha256 git_executable_sha256 ps_executable_sha256
    uptime_executable_sha256 process_tree_contract source)],
    'schema authority fields and nesting exactly match the implementation');
is_deeply([sort keys %{$schema->{identity}}], [sort qw(
    baseline_source_commit candidate_source_commit candidate_parent_commit
    perl5_commit benchmark ordinary_performance_producer performance_evaluator
    perl_interpreter execution_environment jfc jdk_executable jdk_version_log
    baseline_jar candidate_jar baseline_launcher candidate_launcher
    interpreter_launcher jfr_tool time_executable git_executable ps_executable
    uptime_executable ordered_test_source ordered_fixture_manifest
    ordered_fixture_tree_manifest dbix_archive jfr_metrics_producer)],
    'schema identity field set exactly matches the implementation');
is_deeply($schema->{a232_cli}{checker_required}, [qw(--evidence --java --perl
    --git --ps --uptime --authority-key --baseline-source --candidate-source
    --perl5-source)], 'schema binds the A232 checker CLI exactly');
for my $field (qw(process_tree_contract windows_process_tree_policy
        authority_key_unix_mode authority_key_windows_policy)) {
    is($schema->{a232_cli}{$field}, $requirements->{$field},
        "$field is identically policy-bound for A232");
}

done_testing;

sub read_raw {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!";
    my $text = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!";
    return $text;
}
