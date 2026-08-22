#!/usr/bin/env perl
use strict;
use warnings;
use Cwd qw(abs_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IPC::Open3;
use Symbol qw(gensym);
use Test::More;

my $helper = abs_path(File::Spec->catfile($FindBin::Bin, '..', 'update_perl5.pl'));
my $tmp = tempdir('perlonjava-perl5-update-XXXXXX', TMPDIR => 1, CLEANUP => 1);
my $seed = File::Spec->catdir($tmp, 'seed');
my $upstream = File::Spec->catdir($tmp, 'upstream.git');
my $checkout = File::Spec->catdir($tmp, 'perl checkout');

git($tmp, 'init', '-q', $seed);
git($seed, 'config', 'user.name', 'Import Test');
git($seed, 'config', 'user.email', 'import-test@example.invalid');
write_file(File::Spec->catfile($seed, 'tracked.txt'), "one\n");
git($seed, 'add', 'tracked.txt');
git($seed, 'commit', '-q', '-m', 'initial');
git($seed, 'branch', '-M', 'blead');
git($tmp, 'init', '-q', '--bare', $upstream);
git($seed, 'remote', 'add', 'origin', $upstream);
git($seed, 'push', '-q', '-u', 'origin', 'blead');
git_dir($upstream, 'symbolic-ref', 'HEAD', 'refs/heads/blead');
my $initial = git_output($seed, 'rev-parse', 'HEAD');

my ($status, $out, $err) = run_helper(
    '--perl-root', $checkout,
    '--repository', $upstream,
);
is($status, 0, 'absent checkout is cloned from configured upstream') or diag $err;
is(git_output($checkout, 'rev-parse', 'HEAD'), $initial, 'clone consumes latest upstream commit');
like($out, qr/Perl upstream commit: \Q$initial\E/, 'clone prints exact consumed commit');
like($out, qr/Verified remote tip: \Q$initial\E/,
    'clone binds the consumed commit to a fresh remote-tip advertisement');

write_file(File::Spec->catfile($seed, 'tracked.txt'), "two\n");
git($seed, 'add', 'tracked.txt');
git($seed, 'commit', '-q', '-m', 'upstream fast forward');
git($seed, 'push', '-q');
my $second = git_output($seed, 'rev-parse', 'HEAD');
($status, $out, $err) = run_helper('--perl-root', $checkout, '--repository', $upstream);
is($status, 0, 'clean checkout fast-forwards') or diag $err;
is(git_output($checkout, 'rev-parse', 'HEAD'), $second, 'fast-forward reaches latest upstream tip');
like($out, qr/Perl upstream commit: \Q$second\E/, 'fast-forward prints exact consumed commit');
like($out, qr/Verified remote tip: \Q$second\E/,
    'fast-forward binds the consumed commit to the advertised remote tip');

write_file(File::Spec->catfile($checkout, 'generated-untracked.txt'), "generated\n");
($status, $out, $err) = run_helper('--perl-root', $checkout, '--repository', $upstream);
is($status, 0, 'untracked generated files do not make checkout dirty') or diag $err;

my $tracking_before_dirty = git_output($checkout, 'rev-parse', 'origin/blead');
write_file(File::Spec->catfile($checkout, 'tracked.txt'), "local edit\n");
write_file(File::Spec->catfile($seed, 'tracked.txt'), "three\n");
git($seed, 'add', 'tracked.txt');
git($seed, 'commit', '-q', '-m', 'upstream while dirty');
git($seed, 'push', '-q');
($status, $out, $err) = run_helper('--perl-root', $checkout, '--repository', $upstream);
isnt($status, 0, 'tracked local edit is refused');
like($err, qr/tracked local changes exist/, 'dirty refusal explains the safety condition');
is(git_output($checkout, 'rev-parse', 'origin/blead'), $tracking_before_dirty,
    'dirty checkout is refused before fetch');
is(git_output($checkout, 'rev-parse', 'HEAD'), $second, 'dirty refusal leaves HEAD unchanged');

write_file(File::Spec->catfile($checkout, 'tracked.txt'), "two\n");
($status, $out, $err) = run_helper('--perl-root', $checkout, '--repository', $upstream);
is($status, 0, 'checkout updates after tracked edit is removed') or diag $err;
my $third = git_output($checkout, 'rev-parse', 'HEAD');

git($checkout, 'config', 'user.name', 'Import Test');
git($checkout, 'config', 'user.email', 'import-test@example.invalid');
write_file(File::Spec->catfile($checkout, 'local.txt'), "local commit\n");
git($checkout, 'add', 'local.txt');
git($checkout, 'commit', '-q', '-m', 'local commit');
my $local_head = git_output($checkout, 'rev-parse', 'HEAD');
write_file(File::Spec->catfile($seed, 'upstream.txt'), "remote commit\n");
git($seed, 'add', 'upstream.txt');
git($seed, 'commit', '-q', '-m', 'divergent upstream commit');
git($seed, 'push', '-q');
($status, $out, $err) = run_helper('--perl-root', $checkout, '--repository', $upstream);
isnt($status, 0, 'non-fast-forward checkout is refused');
like($err, qr/not a fast-forward/, 'non-fast-forward refusal is actionable');
is(git_output($checkout, 'rev-parse', 'HEAD'), $local_head, 'non-fast-forward refusal leaves HEAD unchanged');

my $real_checkout = File::Spec->catdir($tmp, 'adjacent-perl5');
($status, $out, $err) = run_helper('--perl-root', $real_checkout, '--repository', $upstream);
is($status, 0, 'second clean checkout is cloned') or diag $err;
my $linked_checkout = File::Spec->catfile($tmp, 'perl5-link');
symlink $real_checkout, $linked_checkout or die "Cannot create symlink: $!";
($status, $out, $err) = run_helper('--perl-root', $linked_checkout, '--repository', $upstream);
is($status, 0, 'symlink to adjacent checkout is supported') or diag $err;
my $latest = git_output($seed, 'rev-parse', 'HEAD');
like($out, qr/Perl upstream commit: \Q$latest\E/, 'symlink update reports exact provenance');

my $wrong_repo = File::Spec->catdir($tmp, 'wrong.git');
git($tmp, 'init', '-q', '--bare', $wrong_repo);
($status, $out, $err) = run_helper('--perl-root', $real_checkout, '--repository', $wrong_repo);
isnt($status, 0, 'unexpected configured repository is refused');
like($err, qr/does not match/, 'repository refusal identifies unsafe relationship');

my $fake_sync = File::Spec->catfile($tmp, 'fake-sync.pl');
my $sync_log = File::Spec->catfile($tmp, 'sync-args');
write_file($fake_sync, <<'FAKE_SYNC');
use strict;
use warnings;
open my $fh, '>', $ENV{SYNC_LOG} or die $!;
print {$fh} join "\0", @ARGV;
close $fh or die $!;
FAKE_SYNC
($status, $out, $err) = run_helper(
    { SYNC_LOG => $sync_log }, '--perl-root', $real_checkout,
    '--repository', $upstream, '--sync', '--sync-script', $fake_sync,
);
is($status, 0, 'full sync invocation succeeds against local fixture') or diag $err;
is(read_file($sync_log), '', 'full sync passes no filter arguments');

($status, $out, $err) = run_helper(
    { SYNC_LOG => $sync_log }, '--perl-root', $real_checkout,
    '--repository', $upstream, '--sync', '--filter', 'Name.pl',
    '--sync-script', $fake_sync,
);
is($status, 0, 'filtered sync invocation succeeds against local fixture') or diag $err;
is(read_file($sync_log), "--only\0Name.pl", 'filtered sync passes exactly one --only argument pair');

($status, $out, $err) = run_helper(
    { SYNC_LOG => $sync_log }, '--perl-root', $real_checkout,
    '--repository', $upstream, '--sync', '--verify-idempotent',
    '--sync-script', $fake_sync,
);
is($status, 0, 'idempotence verification is forwarded to the sync implementation')
    or diag $err;
is(read_file($sync_log), '--verify-idempotent',
    'full verification passes exactly one idempotence option');

($status, $out, $err) = run_helper(
    '--perl-root', $real_checkout, '--repository', $upstream,
    '--verify-idempotent',
);
isnt($status, 0, 'idempotence verification requires an import sync');
like($err, qr/--verify-idempotent requires --sync/,
    'missing sync has an actionable verification diagnostic');

done_testing();

sub run_helper {
    my $environment = ref $_[0] eq 'HASH' ? shift : {};
    local %ENV = (%ENV, %$environment);
    my $stderr = gensym;
    my $pid = open3(undef, my $stdout, $stderr, $^X, $helper, @_);
    my $out = do { local $/; <$stdout> // '' };
    my $err = do { local $/; <$stderr> // '' };
    waitpid $pid, 0;
    return ($? >> 8, $out, $err);
}

sub git {
    my ($directory, @arguments) = @_;
    system 'git', '-C', $directory, @arguments;
    die "git @arguments failed\n" if $? != 0;
}

sub git_dir {
    my ($directory, @arguments) = @_;
    system 'git', '--git-dir', $directory, @arguments;
    die "git --git-dir @arguments failed\n" if $? != 0;
}

sub git_output {
    my ($directory, @arguments) = @_;
    open my $pipe, '-|', 'git', '-C', $directory, @arguments or die $!;
    my $output = do { local $/; <$pipe> // '' };
    close $pipe or die "git @arguments failed\n";
    $output =~ s/\s+\z//;
    return $output;
}

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>', $path or die "Cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!";
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<', $path or die "Cannot read $path: $!";
    local $/;
    return <$fh> // '';
}
