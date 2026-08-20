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
my $tmp = tempdir('perlonjava-perl5-latest-XXXXXX', TMPDIR => 1, CLEANUP => 1);
my $seed = File::Spec->catdir($tmp, 'seed');
my $upstream = File::Spec->catdir($tmp, 'upstream.git');
my $checkout = File::Spec->catdir($tmp, 'checkout');

git($tmp, 'init', '-q', $seed);
git($seed, 'config', 'user.name', 'Import Test');
git($seed, 'config', 'user.email', 'import-test@example.invalid');
write_file(File::Spec->catfile($seed, 'tracked.txt'), "blead one\n");
git($seed, 'add', 'tracked.txt');
git($seed, 'commit', '-q', '-m', 'blead initial');
git($seed, 'branch', '-M', 'blead');
git($tmp, 'init', '-q', '--bare', $upstream);
git($seed, 'remote', 'add', 'origin', $upstream);
git($seed, 'push', '-q', '-u', 'origin', 'blead');
git_dir($upstream, 'symbolic-ref', 'HEAD', 'refs/heads/blead');

my ($status, $out, $err) = run_helper(
    '--perl-root', $checkout,
    '--repository', $upstream,
);
is($status, 0, 'default upstream branch is accepted') or diag $err;

git($seed, 'checkout', '-q', '-b', 'maint');
write_file(File::Spec->catfile($seed, 'tracked.txt'), "maint one\n");
git($seed, 'add', 'tracked.txt');
git($seed, 'commit', '-q', '-m', 'maint initial');
git($seed, 'push', '-q', '-u', 'origin', 'maint');
git($checkout, 'fetch', '-q', 'origin', 'maint:refs/remotes/origin/maint');
git($checkout, 'checkout', '-q', '-b', 'maint', '--track', 'origin/maint');

git($seed, 'checkout', '-q', 'blead');
write_file(File::Spec->catfile($seed, 'latest.txt'), "latest\n");
git($seed, 'add', 'latest.txt');
git($seed, 'commit', '-q', '-m', 'latest blead');
git($seed, 'push', '-q');

my $head_before = git_output($checkout, 'rev-parse', 'HEAD');
my $tracking_before = git_output($checkout, 'rev-parse', 'origin/blead');
($status, $out, $err) = run_helper(
    '--perl-root', $checkout,
    '--repository', $upstream,
);
isnt($status, 0, 'clean maintenance branch is refused');
like($err, qr/branch maint is not the latest upstream branch blead/,
    'refusal identifies the remote default branch');
is(git_output($checkout, 'symbolic-ref', '--short', 'HEAD'), 'maint',
    'helper does not switch branches');
is(git_output($checkout, 'rev-parse', 'HEAD'), $head_before,
    'helper does not move the maintenance checkout');
is(git_output($checkout, 'rev-parse', 'origin/blead'), $tracking_before,
    'non-default branch is refused before fetch');

done_testing;

sub run_helper {
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
