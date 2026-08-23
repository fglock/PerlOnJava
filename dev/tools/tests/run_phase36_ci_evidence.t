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
use Symbol qw(gensym);
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools', 'run_phase36_ci_evidence.pl');
my $tmp = abs_path(tempdir(CLEANUP => 1));
my $source = File::Spec->catdir($tmp, 'source');
my $api = File::Spec->catdir($tmp, 'api');
make_path(File::Spec->catdir($source, '.github', 'workflows'), $api);
my $workflow_rel = '.github/workflows/gradle.yml';
my $workflow = File::Spec->catfile($source, split m{/}, $workflow_rel);
write_raw($workflow, "name: Java CI with Gradle\n");
my $sha = 'a' x 40;
my $repo = 'trusted/PerlOnJava';
my $ubuntu = 'build (ubuntu-latest)';
my $windows = 'build (windows-latest)';
$ENV{FAKE_SOURCE} = $source;
$ENV{FAKE_API} = $api;
my $git = fake_git();
my $gh = fake_gh();
write_fixtures();

my @base = ($^X, $tool, '--source-dir', $source, '--git', $git,
    '--offline-api-dir', $api, '--expected-commit', $sha, '--repository', $repo,
    '--workflow-id', 77, '--workflow-name', 'Java CI with Gradle',
    '--workflow-file', $workflow_rel, '--ubuntu-check', $ubuntu,
    '--windows-check', $windows, '--timeout', 2, '--poll-interval', 1);

my $output = File::Spec->catfile($tmp, 'success.json');
my ($status, $text) = run_tool(@base, '--output', $output);
diag($text) if $status;
is($status, 0, 'offline final-freeze evidence succeeds');
ok(-f $output, 'success artifact is published');
my $artifact = decode(read_raw($output));
ok($artifact->{verified}, 'artifact is explicitly verified');
is($artifact->{authority}{source_commit}, $sha, 'exact source SHA is sealed');
is($artifact->{authority}{workflow}{sha256}, sha256_hex(read_raw($workflow)),
    'local workflow bytes are sealed');
is($artifact->{authority}{run}{run_attempt}, 1, 'first attempt is sealed');
is_deeply([map { $_->{id} } @{$artifact->{authority}{jobs}}], [501, 502],
    'required job IDs are sealed');
is_deeply([map { $_->{check_suite_id} } @{$artifact->{authority}{checks}}], [900, 900],
    'check-suite binding is sealed');
ok(!exists($artifact->{authority}{checks}[0]{details_url}),
    'mutable GitHub URLs are excluded from authority');
is((stat($output))[2] & 0777, 0600, 'published artifact has sealed private mode');
ok(@{$artifact->{raw_api_evidence}} >= 6, 'complete tool and API evidence is embedded');
is($artifact->{tools}{git}{sha256}, sha256_hex(read_raw($git)),
    'trusted Git executable bytes are snapshotted and sealed');
ok(!exists($artifact->{tools}{gh}{sha256}),
    'offline mode does not claim or invoke a GitHub CLI executable');
for my $record (@{$artifact->{raw_api_evidence}}) {
    like($record->{sha256}, qr/\A[0-9a-f]{64}\z/, "$record->{label} has a hash");
    ok(length($record->{base64}) || $record->{size} == 0,
        "$record->{label} retains complete raw bytes");
}
my $payload = {%$artifact};
delete $payload->{seal};
is($artifact->{seal}{payload_sha256},
    sha256_hex(JSON::PP->new->utf8->canonical->encode($payload)),
    'self-seal covers the canonical structured payload');

write_fixtures();
write_raw(File::Spec->catfile($api, 'runs-002.json'),
    read_raw(File::Spec->catfile($api, 'runs-001.json')));
mutate('runs-001.json', sub {
    $_[0]{workflow_runs}[0]{status} = 'in_progress';
    $_[0]{workflow_runs}[0]{conclusion} = undef;
});
my $polled_output = File::Spec->catfile($tmp, 'polled-success.json');
($status, $text) = run_tool(@base, '--output', $polled_output);
is($status, 0, 'offline fixtures deterministically model bounded polling');
ok(grep($_->{label} eq 'api:runs-2',
        @{decode(read_raw($polled_output))->{raw_api_evidence}}),
    'all polling responses are retained as raw evidence');

my @cases = (
    ['dirty source', sub { local $ENV{FAKE_GIT_DIRTY} = 1; run_case(@_) }, qr/not clean/],
    ['wrong local SHA', sub { local $ENV{FAKE_GIT_SHA} = 'b' x 40; run_case(@_) }, qr/not exact/],
    ['wrong repository', sub { mutate('runs-001.json', sub { $_[0]{workflow_runs}[0]{repository}{full_name} = 'evil/fork' }); run_case(@_) }, qr/Wrong-repository/],
    ['wrong SHA', sub { mutate('runs-001.json', sub { $_[0]{workflow_runs}[0]{head_sha} = 'b' x 40 }); run_case(@_) }, qr/Wrong-SHA/],
    ['wrong workflow metadata', sub { mutate('workflow.json', sub { $_[0]{id} = 78 }); run_case(@_) }, qr/Wrong workflow ID/],
    ['wrong workflow run', sub { mutate('runs-001.json', sub { $_[0]{workflow_runs}[0]{workflow_id} = 78 }); run_case(@_) }, qr/Wrong-workflow/],
    ['ambiguous runs', sub { mutate('runs-001.json', sub { push @{$_[0]{workflow_runs}}, {%{$_[0]{workflow_runs}[0]}, id => 101}; $_[0]{total_count} = 2 }); run_case(@_) }, qr/Ambiguous workflow runs/],
    ['missing run', sub { mutate('runs-001.json', sub { $_[0]{workflow_runs} = []; $_[0]{total_count} = 0 }); run_case(@_) }, qr/missing/],
    ['rerun attempt', sub { mutate('runs-001.json', sub { $_[0]{workflow_runs}[0]{run_attempt} = 2 }); run_case(@_) }, qr/Rerun/],
    ['in-progress run', sub { mutate('runs-001.json', sub { $_[0]{workflow_runs}[0]{status} = 'in_progress'; $_[0]{workflow_runs}[0]{conclusion} = undef }); run_case(@_) }, qr/in-progress/],
    ['failed run', sub { mutate('runs-001.json', sub { $_[0]{workflow_runs}[0]{conclusion} = 'failure' }); run_case(@_) }, qr/conclusion is not success/],
    ['skipped job', sub { mutate('jobs.json', sub { $_[0]{jobs}[0]{conclusion} = 'skipped' }); run_case(@_) }, qr/Job conclusion is not success/],
    ['neutral check', sub { mutate('checks.json', sub { $_[0]{check_runs}[0]{conclusion} = 'neutral' }); run_case(@_) }, qr/Check run conclusion is not success/],
    ['missing required job', sub { mutate('jobs.json', sub { shift @{$_[0]{jobs}}; $_[0]{total_count}-- }); run_case(@_) }, qr/Missing required workflow job/],
    ['ambiguous required check', sub { mutate('checks.json', sub { push @{$_[0]{check_runs}}, {%{$_[0]{check_runs}[0]}, id => 999}; $_[0]{total_count}++ }); run_case(@_) }, qr/Ambiguous required check run/],
    ['wrong check SHA', sub { mutate('checks.json', sub { $_[0]{check_runs}[0]{head_sha} = 'b' x 40 }); run_case(@_) }, qr/Check run has wrong SHA/],
    ['stale suite', sub { mutate('checks.json', sub { $_[0]{check_runs}[0]{check_suite}{id} = 899 }); run_case(@_) }, qr/Stale check run/],
    ['stale timestamp', sub { mutate('runs-001.json', sub { $_[0]{workflow_runs}[0]{created_at} = '2025-12-31T23:59:59Z' }); run_case(@_) }, qr/predates/],
    ['incomplete pagination', sub { mutate('checks.json', sub { $_[0]{total_count}++ }); run_case(@_) }, qr/requires pagination/],
    ['oversized fixture', sub { write_raw(File::Spec->catfile($api, 'checks.json'), 'x' x 2048); run_case(@_, '--max-api-bytes', 1024) }, qr/exceeds bounded size/],
);

for my $case (@cases) {
    write_fixtures();
    my $case_output = File::Spec->catfile($tmp, "reject-$$-" . int(rand(1_000_000)) . '.json');
    my ($case_status, $case_text) = $case->[1]->($case_output);
    isnt($case_status, 0, "$case->[0] is rejected");
    like($case_text, $case->[2], "$case->[0] has a specific diagnostic");
    ok(!-e $case_output, "$case->[0] publishes no partial artifact");
}

write_fixtures();
my $existing = File::Spec->catfile($tmp, 'existing.json');
write_raw($existing, "do not replace\n");
($status, $text) = run_tool(@base, '--output', $existing);
isnt($status, 0, 'pre-existing output is rejected');
is(read_raw($existing), "do not replace\n", 'pre-existing output remains byte-identical');

write_fixtures();
my $outside = File::Spec->catfile($tmp, 'outside.json');
my $link = File::Spec->catfile($api, 'checks.json');
unlink $link or die $!;
symlink $outside, $link or die $!;
my $link_output = File::Spec->catfile($tmp, 'symlink-reject.json');
($status, $text) = run_tool(@base, '--output', $link_output);
isnt($status, 0, 'symlinked offline evidence is rejected');
like($text, qr/non-symlink/, 'symlink rejection is explicit');
ok(!-e $link_output, 'symlink rejection publishes no artifact');

write_fixtures();
my $record = File::Spec->catfile($tmp, 'gh-calls');
local $ENV{FAKE_GH_RECORD} = $record;
my @live = grep { $_ ne '--offline-api-dir' && $_ ne $api } @base;
push @live, '--gh', $gh;
my $live_output = File::Spec->catfile($tmp, 'live-fake.json');
($status, $text) = run_tool(@live, '--output', $live_output);
is($status, 0, 'fake trusted GitHub CLI path succeeds without a shell');
my $calls = read_raw($record);
like($calls, qr/actions\/workflows\/77/, 'fake CLI receives workflow endpoint as one argument');
unlike($calls, qr/[;|`]/, 'recorded API arguments contain no shell control operators');
my $live_artifact = decode(read_raw($live_output));
is($live_artifact->{tools}{gh}{sha256}, sha256_hex(read_raw($gh)),
    'canonical GitHub CLI bytes are snapshotted and sealed in live mode');

write_fixtures();
my $mutated_output = File::Spec->catfile($tmp, 'mutated-cli.json');
local $ENV{FAKE_GH_MUTATE} = 1;
($status, $text) = run_tool(@live, '--output', $mutated_output);
isnt($status, 0, 'GitHub CLI mutation during execution is rejected');
like($text, qr/identity changed immediately after execution/,
    'post-execution identity failure is explicit');
ok(!-e $mutated_output, 'mutated executable publishes no artifact');

done_testing;

sub run_case {
    my ($path, @extra) = @_;
    return run_tool(@base, @extra, '--output', $path);
}
sub run_tool {
    my @command = @_;
    my $err = gensym;
    my $pid = open3(undef, my $out, $err, @command);
    my $stdout = do { local $/; <$out> } // '';
    my $stderr = do { local $/; <$err> } // '';
    waitpid($pid, 0);
    return ($? >> 8, $stdout . $stderr);
}
sub mutate {
    my ($name, $callback) = @_;
    my $path = File::Spec->catfile($api, $name);
    my $doc = decode(read_raw($path));
    $callback->($doc);
    write_json($path, $doc);
}
sub write_fixtures {
    unlink glob(File::Spec->catfile($api, '*'));
    write_json(File::Spec->catfile($api, 'workflow.json'), {
        id => 77, name => 'Java CI with Gradle', path => $workflow_rel, state => 'active'});
    write_json(File::Spec->catfile($api, 'commit.json'), {sha => $sha,
        commit => {committer => {date => '2026-01-01T00:00:00Z'}}});
    my $run = {id => 100, run_number => 44, run_attempt => 1, workflow_id => 77,
        check_suite_id => 900, head_sha => $sha, event => 'push', status => 'completed',
        conclusion => 'success', created_at => '2026-01-01T00:01:00Z',
        updated_at => '2026-01-01T00:10:00Z', path => $workflow_rel,
        repository => {full_name => $repo}};
    write_json(File::Spec->catfile($api, 'runs-001.json'),
        {total_count => 1, workflow_runs => [$run]});
    my @jobs = map { my ($id, $name) = @$_; {id => $id, run_id => 100,
        run_attempt => 1, name => $name, head_sha => $sha, status => 'completed',
        conclusion => 'success', started_at => '2026-01-01T00:02:00Z',
        completed_at => '2026-01-01T00:09:00Z'} } ([501, $ubuntu], [502, $windows]);
    write_json(File::Spec->catfile($api, 'jobs.json'), {total_count => 2, jobs => \@jobs});
    my @checks = map { my ($id, $name) = @$_; {id => $id, name => $name,
        head_sha => $sha, status => 'completed', conclusion => 'success',
        started_at => '2026-01-01T00:02:00Z', completed_at => '2026-01-01T00:09:00Z',
        check_suite => {id => 900}, app => {id => 15368, slug => 'github-actions'},
        details_url => 'https://github.example.invalid/mutable'} } ([501, $ubuntu], [502, $windows]);
    write_json(File::Spec->catfile($api, 'checks.json'),
        {total_count => 2, check_runs => \@checks});
}
sub fake_git {
    my $path = File::Spec->catfile($tmp, 'trusted-git');
    write_raw($path, <<'FAKE');
#!/usr/bin/env perl
use strict; use warnings;
if (@ARGV == 1 && $ARGV[0] eq '--version') { print "git version fixture\n"; exit }
shift @ARGV if $ARGV[0] eq '-C'; shift @ARGV if @ARGV && $ARGV[0] =~ m{^/};
if ($ARGV[0] eq 'rev-parse' && $ARGV[1] eq '--show-toplevel') { print "$ENV{FAKE_SOURCE}\n" }
elsif ($ARGV[0] eq 'rev-parse') { print(($ENV{FAKE_GIT_SHA} || ('a' x 40)), "\n") }
elsif ($ARGV[0] eq 'status') { print "?? dirty\n" if $ENV{FAKE_GIT_DIRTY} }
elsif ($ARGV[0] eq 'ls-files') { print ".github/workflows/gradle.yml\n" }
elsif ($ARGV[0] eq 'show') { open my $fh, '<:raw', "$ENV{FAKE_SOURCE}/.github/workflows/gradle.yml" or die $!; print while <$fh> }
else { die "unexpected fake git args: @ARGV" }
FAKE
    chmod 0755, $path or die $!;
    return $path;
}
sub fake_gh {
    my $path = File::Spec->catfile($tmp, 'trusted-gh');
    write_raw($path, <<'FAKE');
#!/usr/bin/env perl
use strict; use warnings;
if (@ARGV == 1 && $ARGV[0] eq '--version') { print "gh version fixture\n"; exit }
if ($ENV{FAKE_GH_MUTATE}) {
    open my $self, '>>:raw', $0 or die $!; print {$self} "# mutated\n"; close $self;
}
open my $record, '>>', $ENV{FAKE_GH_RECORD} or die $!;
print {$record} join("\t", @ARGV), "\n"; close $record;
my $endpoint = $ARGV[-1];
my $name = $endpoint =~ m{/check-runs} ? 'checks.json'
    : $endpoint =~ m{/attempts/1/jobs} ? 'jobs.json'
    : $endpoint =~ m{/runs\?} ? 'runs-001.json'
    : $endpoint =~ m{/commits/} ? 'commit.json' : 'workflow.json';
open my $fh, '<:raw', "$ENV{FAKE_API}/$name" or die $!; print while <$fh>;
FAKE
    chmod 0755, $path or die $!;
    return $path;
}
sub write_json { write_raw($_[0], JSON::PP->new->canonical->encode($_[1])) }
sub decode { JSON::PP->new->decode($_[0]) }
sub write_raw {
    my ($path, $bytes) = @_;
    unlink $path if -l $path;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!";
    print {$fh} $bytes or die $!;
    close $fh or die $!;
}
sub read_raw {
    open my $fh, '<:raw', $_[0] or die "Cannot read $_[0]: $!";
    return do { local $/; <$fh> };
}
