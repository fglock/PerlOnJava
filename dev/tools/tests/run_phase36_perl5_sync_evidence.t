use strict;
use warnings;

use Cwd qw(abs_path);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $project = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($project, qw(dev tools run_phase36_perl5_sync_evidence.pl));
my $git = command_path('git');
my $perl = command_path('perl');

subtest 'successful full two-pass capture is identity-bound and atomic' => sub {
    my $fixture = fixture('success space');
    my ($status, $text) = run_tool($fixture);
    is($status, 0, 'producer succeeds');
    is($text, "$fixture->{output}\n", 'producer reports the canonical output');
    my $evidence = json_file($fixture->{output});
    is($evidence->{status}, 'pass', 'artifact is a pass');
    is($evidence->{kind}, 'phase36-perl5-sync-evidence', 'artifact kind is explicit');
    is($evidence->{expected_source_commit}, $fixture->{source_sha}, 'source authority retained');
    is($evidence->{final_source_commit}, $fixture->{source_sha}, 'final source identity retained');
    is($evidence->{perl5}{after}{commit}, $fixture->{perl_sha}, 'latest Perl identity retained');
    is($evidence->{upstream}{after}{tip}, $fixture->{perl_sha}, 'advertised tip retained');
    is($evidence->{sync_markers}{pass_count}, 2, 'both passes proven');
    ok($evidence->{sync_markers}{idempotence_verified}, 'idempotence proven');
    is(scalar @{$evidence->{protected_targets}}, 1, 'protected manifest retained');
    like($evidence->{command}{complete_log}, qr/Idempotence verified/, 'complete log embedded');
    ok($evidence->{unicode_name}{after}{upstream}{sha256}, 'upstream Name.pl hashed');
    ok($evidence->{unicode_name}{after}{imported}{sha256}, 'imported Name.pl hashed');
    is_deeply([sort grep { /\.(?:stage|log)-/ } directory_entries($fixture->{dir})], [],
        'private staging files are removed');
};

subtest 'remote latest-tip mismatch is rejected' => sub {
    my $fixture = fixture('latest mismatch');
    advance_remote($fixture);
    rejected($fixture, qr/(?:upstream commit markers|latest tip)/,
        'checkout not advanced to the newly advertised tip');
};

subtest 'partial sync and missing second-pass evidence are rejected' => sub {
    my $partial = fixture('partial');
    $partial->{mode} = 'partial';
    rejected($partial, qr/FILTER\/partial-sync/, 'filtered mode');

    my $second = fixture('second');
    $second->{mode} = 'missing_second';
    rejected($second, qr/second-pass marker/, 'missing second pass');
};

subtest 'both Name.pl artifacts are mandatory' => sub {
    my $upstream = fixture('missing upstream name');
    unlink File::Spec->catfile($upstream->{perl5}, qw(lib unicore Name.pl)) or die $!;
    git_commit($upstream->{perl5}, 'remove generated upstream Name.pl');
    command($git, '-C', $upstream->{perl5}, 'push', 'origin', 'master');
    $upstream->{perl_sha} = git_line($upstream->{perl5}, 'rev-parse', 'HEAD');
    $upstream->{mode} = 'missing_name';
    rejected($upstream, qr/upstream unicore\/Name\.pl is missing after sync/,
        'missing upstream Name.pl');

    my $imported = fixture('missing imported name');
    unlink File::Spec->catfile($imported->{source}, qw(src main perl lib unicore Name.pl)) or die $!;
    git_commit($imported->{source}, 'remove imported Name.pl');
    $imported->{source_sha} = git_line($imported->{source}, 'rev-parse', 'HEAD');
    $imported->{mode} = 'missing_name';
    rejected($imported, qr/imported unicore\/Name\.pl is missing after sync/,
        'missing imported Name.pl');
};

subtest 'dirty final source and nonzero runs fail closed' => sub {
    my $dirty = fixture('dirty');
    $dirty->{mode} = 'dirty';
    rejected($dirty, qr/Source worktree is dirty after/, 'post-command source mutation');

    my $perl_dirty = fixture('perl dirty');
    $perl_dirty->{mode} = 'perl_dirty';
    rejected($perl_dirty, qr/Perl5 worktree is not clean .* after/,
        'post-command Perl5 mutation');

    my $perl_untracked = fixture('perl untracked');
    $perl_untracked->{mode} = 'perl_untracked';
    rejected($perl_untracked, qr/Perl5 worktree is not clean .* after/,
        'unexpected untracked Perl5 output');

    my $nonzero = fixture('nonzero');
    $nonzero->{mode} = 'nonzero';
    rejected($nonzero, qr/exited with status 9/, 'nonzero command');
};

subtest 'the sole generated upstream Name.pl exception is explicit and hash-bound' => sub {
    my $fixture = fixture('generated name allowance');
    unlink File::Spec->catfile($fixture->{perl5}, qw(lib unicore Name.pl)) or die $!;
    git_commit($fixture->{perl5}, 'omit generated upstream Name.pl');
    command($git, '-C', $fixture->{perl5}, 'push', 'origin', 'master');
    $fixture->{perl_sha} = git_line($fixture->{perl5}, 'rev-parse', 'HEAD');
    $fixture->{mode} = 'generate_name';
    my ($status, $text) = run_tool($fixture);
    is($status, 0, 'generated upstream Name.pl is the only accepted untracked path');
    my $evidence = json_file($fixture->{output});
    is_deeply($evidence->{perl5}{after}{allowed_generated_untracked},
        ['lib/unicore/Name.pl'], 'allowed untracked path is explicit');
    is_deeply($evidence->{perl5}{after}{unexpected_untracked}, [],
        'no other untracked paths are hidden');
    ok($evidence->{unicode_name}{after}{upstream}{sha256},
        'allowed generated file is hash-bound');
};

subtest 'timeout kills the bounded run and publishes nothing' => sub {
    my $fixture = fixture('timeout');
    $fixture->{mode} = 'timeout';
    $fixture->{timeout} = 1;
    rejected($fixture, qr/timed out after 1s/, 'timeout');
};

subtest 'argv and repository metacharacters are not shell evaluated' => sub {
    my $fixture = fixture('literal ; touch SHOULD_NOT_EXIST');
    my $sentinel = File::Spec->catfile($fixture->{dir}, 'SHOULD_NOT_EXIST');
    my ($status, $text) = run_tool($fixture);
    is($status, 0, 'metacharacter paths are passed literally');
    ok(!-e $sentinel, 'no injected shell command ran');
    my $evidence = json_file($fixture->{output});
    is($evidence->{command}{argv}[2], abs_path($fixture->{source}),
        'source path is one argv element');
};

subtest 'preexisting output is never replaced and no partial output survives' => sub {
    my $fixture = fixture('collision');
    write_file($fixture->{output}, "authority\n");
    my ($status, $text) = run_tool($fixture);
    isnt($status, 0, 'output collision rejects');
    like($text, qr/Refusing to overwrite output/, 'collision diagnostic is specific');
    is(read_file($fixture->{output}), "authority\n", 'existing authority remains byte-identical');

    my $failure = fixture('cleanup');
    $failure->{mode} = 'missing_second';
    rejected($failure, qr/second-pass marker/, 'failed publication');
    is_deeply([grep { /\.(?:stage|log)-/ } directory_entries($failure->{dir})], [],
        'failure removes private staging and log files');
};

done_testing;

sub fixture {
    my ($label) = @_;
    my $dir = tempdir("a236 $label XXXX", TMPDIR => 1, CLEANUP => 1);
    my $seed = File::Spec->catdir($dir, 'upstream seed');
    my $bare = File::Spec->catdir($dir, 'upstream ; bare.git');
    my $perl5 = File::Spec->catdir($dir, 'perl checkout');
    make_path(File::Spec->catdir($seed, qw(lib unicore)));
    git_init($seed);
    write_file(File::Spec->catfile($seed, qw(lib unicore Name.pl)), "upstream names\n");
    write_file(File::Spec->catfile($seed, 'README'), "perl upstream\n");
    git_commit($seed, 'initial upstream');
    command($git, 'clone', '--bare', $seed, $bare);
    command($git, 'clone', $bare, $perl5);
    my $perl_sha = git_line($perl5, 'rev-parse', 'HEAD');

    my $source = File::Spec->catdir($dir, 'source ; project');
    make_path(File::Spec->catdir($source, qw(dev import-perl5)));
    make_path(File::Spec->catdir($source, qw(src main perl lib unicore)));
    make_path(File::Spec->catdir($source, 'protected'));
    git_init($source);
    write_file(File::Spec->catfile($source, qw(dev import-perl5 config.yaml)), <<'CONFIG');
imports:
  - source: perl5/lib/unicore/Name.pl
    target: src/main/perl/lib/unicore/Name.pl
  - source: perl5/README
    target: protected/kept.txt
    protected: true
CONFIG
    write_file(File::Spec->catfile($source, qw(dev import-perl5 sync.pl)), "fixture sync\n");
    write_file(File::Spec->catfile($source, qw(dev import-perl5 update_perl5.pl)), "fixture update\n");
    write_file(File::Spec->catfile($source, 'Makefile'), "perl5-sync-check:\n\t\@true\n");
    write_file(File::Spec->catfile($source, qw(src main perl lib unicore Name.pl)),
        "imported names\n");
    write_file(File::Spec->catfile($source, qw(protected kept.txt)), "keep me\n");
    symlink $perl5, File::Spec->catfile($source, 'perl5') or die $!;
    git_commit($source, 'synthetic source');
    my $source_sha = git_line($source, 'rev-parse', 'HEAD');

    my $make = fake_make(File::Spec->catfile($dir, 'fake make'));
    return { dir => $dir, seed => $seed, bare => $bare, perl5 => $perl5,
        perl_sha => $perl_sha, source => $source, source_sha => $source_sha,
        make => $make, output => File::Spec->catfile($dir, 'sync evidence.json'),
        timeout => 4, mode => 'success' };
}

sub fake_make {
    my ($path) = @_;
    write_file($path, <<'FAKE');
#!/usr/bin/env perl
use strict;
use warnings;
use Cwd qw(abs_path);
use File::Spec;
my $mode = $ENV{A236_FAKE_MODE} // 'success';
my $source;
for (my $i = 0; $i < @ARGV; $i++) { $source = $ARGV[$i + 1] if $ARGV[$i] eq '-C' }
die "missing -C\n" unless defined $source;
exit 9 if $mode eq 'nonzero';
sleep 5 if $mode eq 'timeout';
if ($mode eq 'dirty') {
    open my $fh, '>>:raw', File::Spec->catfile($source, 'protected', 'kept.txt') or die $!;
    print {$fh} "mutated\n"; close $fh;
}
my $perl5 = abs_path(File::Spec->catdir($source, 'perl5'));
if ($mode eq 'perl_dirty') {
    open my $fh, '>>:raw', File::Spec->catfile($perl5, 'README') or die $!;
    print {$fh} "mutated\n"; close $fh;
}
if ($mode eq 'perl_untracked') {
    open my $fh, '>:raw', File::Spec->catfile($perl5, 'unexpected.tmp') or die $!;
    print {$fh} "unexpected\n"; close $fh;
}
if ($mode eq 'generate_name') {
    my $directory = File::Spec->catdir($perl5, 'lib', 'unicore');
    mkdir File::Spec->catdir($perl5, 'lib') unless -d File::Spec->catdir($perl5, 'lib');
    mkdir $directory unless -d $directory;
    open my $fh, '>:raw', File::Spec->catfile($directory, 'Name.pl') or die $!;
    print {$fh} "generated names\n"; close $fh;
}
open my $pipe, '-|', 'git', '-C', $perl5, 'rev-parse', 'HEAD' or die $!;
my $sha = <$pipe>; close $pipe or die $!; chomp $sha;
print "Perl upstream commit: $sha\n";
print "Verified remote tip: $sha\n";
print "Filtered mode: 1 import(s) matching --only 'Name.pl'\n" if $mode eq 'partial';
for my $pass (1, 2) {
    print "PerlOnJava Perl5 Import Tool\n";
    print "Protected paths from config (1):\n  protected/kept.txt\n\n";
    print "Full manifest: 2 import(s) to process.\n";
    print "Summary:\n  Successful: 2\n  Errors: 0\n";
    print "Running second sync for idempotence verification.\n"
        if $pass == 1 && $mode ne 'missing_second';
}
print "Idempotence verified: second sync changed no imported outputs.\n";
exit 0;
FAKE
    chmod 0755, $path or die $!;
    return abs_path($path);
}

sub run_tool {
    my ($fixture) = @_;
    local $ENV{A236_FAKE_MODE} = $fixture->{mode};
    my @argv = ($^X, $tool,
        '--source-root', $fixture->{source},
        '--perl5-root', $fixture->{perl5},
        '--perl', $perl,
        '--git', $git,
        '--make', $fixture->{make},
        '--repository', $fixture->{bare},
        '--expected-source-commit', $fixture->{source_sha},
        '--output', $fixture->{output},
        '--timeout', $fixture->{timeout});
    return capture(@argv);
}

sub rejected {
    my ($fixture, $pattern, $label) = @_;
    my ($status, $text) = run_tool($fixture);
    isnt($status, 0, "$label rejects");
    like($text, $pattern, "$label has a specific diagnostic");
    ok(!-e $fixture->{output}, "$label publishes no evidence");
}

sub advance_remote {
    my ($fixture) = @_;
    write_file(File::Spec->catfile($fixture->{seed}, 'ADVANCE'), "advance\n");
    git_commit($fixture->{seed}, 'advance upstream');
    command($git, '-C', $fixture->{seed}, 'push', $fixture->{bare}, 'master');
}

sub git_init {
    my ($root) = @_;
    command($git, 'init', '-q', '-b', 'master', $root);
    command($git, '-C', $root, 'config', 'user.name', 'A236 Fixture');
    command($git, '-C', $root, 'config', 'user.email', 'a236@example.invalid');
}

sub git_commit {
    my ($root, $message) = @_;
    command($git, '-C', $root, 'add', '-A');
    command($git, '-C', $root, 'commit', '-q', '-m', $message);
}

sub git_line {
    my ($root, @args) = @_;
    my ($status, $text) = capture($git, '-C', $root, @args);
    die "git failed: $text" if $status;
    $text =~ s/\s+\z//;
    return $text;
}

sub command {
    my (@argv) = @_;
    system @argv;
    die "command failed (@argv): " . ($? >> 8) . "\n" if $?;
}

sub command_path {
    my ($name) = @_;
    my ($status, $text) = capture('/usr/bin/env', 'sh', '-c', 'command -v "$1"', 'sh', $name);
    die "cannot find $name\n" if $status;
    $text =~ s/\s+\z//;
    return abs_path($text);
}

sub capture {
    my (@argv) = @_;
    my $path = File::Spec->catfile(File::Spec->tmpdir, "a236-capture-$$-" . int(rand(1_000_000)));
    my $pid = fork(); die $! unless defined $pid;
    if ($pid == 0) {
        open STDOUT, '>:raw', $path or die $!;
        open STDERR, '>&', STDOUT or die $!;
        exec { $argv[0] } @argv; die $!;
    }
    waitpid($pid, 0); my $status = $? >> 8;
    my $text = read_file($path); unlink $path;
    return ($status, $text);
}

sub write_file {
    my ($path, $bytes) = @_;
    make_path(File::Spec->catdir((File::Spec->splitpath($path))[1]));
    open my $fh, '>:raw', $path or die $!;
    print {$fh} $bytes; close $fh or die $!;
    return $path;
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die $!;
    local $/; my $bytes = <$fh> // ''; close $fh or die $!;
    return $bytes;
}

sub json_file { JSON::PP->new->decode(read_file($_[0])) }

sub directory_entries {
    my ($path) = @_;
    opendir my $dh, $path or die $!;
    my @entry = grep { $_ ne '.' && $_ ne '..' } readdir $dh;
    closedir $dh or die $!;
    return @entry;
}
