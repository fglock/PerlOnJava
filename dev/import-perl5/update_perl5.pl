#!/usr/bin/env perl
use strict;
use warnings;
use Cwd qw(abs_path getcwd);
use File::Spec;
use FindBin;
use Getopt::Long qw(GetOptions);

my $project_root = abs_path(File::Spec->catdir($FindBin::Bin, '..', '..'));
my $perl_root = File::Spec->catdir($project_root, 'perl5');
my $repository = $ENV{PERL5_REPOSITORY} // 'https://github.com/Perl/perl5.git';
my $sync_script = File::Spec->catfile($project_root, 'dev', 'import-perl5', 'sync.pl');
my $sync = 0;
my $verify_idempotent = 0;
my $filter;
my $help = 0;
GetOptions(
    'perl-root=s' => \$perl_root,
    'repository=s' => \$repository,
    'sync-script=s' => \$sync_script,
    'sync' => \$sync,
    'verify-idempotent' => \$verify_idempotent,
    'filter=s' => \$filter,
    'help' => \$help,
) or usage(2);
usage(0) if $help;
die "--filter requires --sync\n" if defined $filter && !$sync;
die "--verify-idempotent requires --sync\n" if $verify_idempotent && !$sync;
die "--filter must be non-empty\n" if defined $filter && !length $filter;
$perl_root = File::Spec->rel2abs($perl_root);

if (!-e $perl_root && !-l $perl_root) {
    run('git', 'clone', '--origin', 'origin', $repository, $perl_root);
} else {
    die "$perl_root is not a Git checkout\n"
        unless -d $perl_root && -d File::Spec->catdir($perl_root, '.git');
}

my $dirty = capture('git', '-C', $perl_root, 'status', '--porcelain', '--untracked-files=no');
die "Refusing to update $perl_root: tracked local changes exist\n" if length $dirty;
my $branch = capture_line('git', '-C', $perl_root, 'symbolic-ref', '--quiet', '--short', 'HEAD');
die "Refusing to update $perl_root: detached HEAD\n" unless length $branch;
my $upstream = capture_line('git', '-C', $perl_root, 'rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{upstream}');
die "Refusing to update $perl_root: branch $branch has no upstream\n" unless length $upstream;
my ($remote) = split m{/}, $upstream, 2;
die "Refusing to update $perl_root: invalid upstream $upstream\n" unless defined $remote && length $remote;
my $remote_url = capture_line('git', '-C', $perl_root, 'remote', 'get-url', $remote);
die "Refusing to update $perl_root: upstream remote $remote_url does not match $repository\n"
    unless normalized_repository($remote_url) eq normalized_repository($repository);
my $latest_branch = remote_default_branch($perl_root, $remote);
die "Refusing to update $perl_root: checked-out branch $branch is not the latest upstream branch $latest_branch\n"
    unless $branch eq $latest_branch;
die "Refusing to update $perl_root: upstream $upstream does not track $remote/$latest_branch\n"
    unless $upstream eq "$remote/$latest_branch";

run('git', '-C', $perl_root, 'fetch', $remote);
my $advertised_tip = remote_branch_tip($perl_root, $remote, $latest_branch);
my $fetched_tip = capture_line('git', '-C', $perl_root, 'rev-parse', $upstream);
die "Refusing to update $perl_root: $upstream differs from the freshly advertised remote tip; retry the update\n"
    unless $fetched_tip eq $advertised_tip;
unless (succeeds('git', '-C', $perl_root, 'merge-base', '--is-ancestor', 'HEAD', $upstream)) {
    die "Refusing to update $perl_root: $upstream is not a fast-forward of HEAD\n";
}
run('git', '-C', $perl_root, 'merge', '--ff-only', $upstream);
my $commit = capture_line('git', '-C', $perl_root, 'rev-parse', 'HEAD');
die "Refusing to update $perl_root: merged commit differs from advertised remote tip\n"
    unless $commit eq $advertised_tip;
print "Perl upstream commit: $commit\n";
print "Verified remote tip: $advertised_tip\n";

if ($sync) {
    my @command = ($^X, $sync_script);
    push @command, '--only', $filter if defined $filter;
    push @command, '--verify-idempotent' if $verify_idempotent;
    my $old = getcwd();
    chdir $project_root or die "Cannot enter $project_root: $!\n";
    run(@command);
    chdir $old or die "Cannot restore $old: $!\n";
}

sub normalized_repository {
    my ($value) = @_;
    $value =~ s{\Agit\@github\.com:}{https://github.com/}i;
    $value =~ s{\Assh://git\@github\.com/}{https://github.com/}i;
    $value =~ s{/+\z}{};
    $value =~ s{\.git\z}{};
    return $value;
}

sub remote_default_branch {
    my ($checkout, $remote) = @_;
    my $advertisement = capture('git', '-C', $checkout, 'ls-remote', '--symref', $remote, 'HEAD');
    my ($branch) = $advertisement =~ /^ref:\s+refs\/heads\/(\S+)\s+HEAD$/m;
    die "Refusing to update $checkout: cannot determine the latest branch advertised by $remote\n"
        unless defined $branch && length $branch;
    return $branch;
}

sub remote_branch_tip {
    my ($checkout, $remote, $branch) = @_;
    my $reference = "refs/heads/$branch";
    my $advertisement = capture(
        'git', '-C', $checkout, 'ls-remote', $remote, $reference);
    my @matches = $advertisement =~ /^([0-9a-f]{40})\s+\Q$reference\E$/mg;
    die "Refusing to update $checkout: cannot determine the latest tip advertised for $remote/$branch\n"
        unless @matches == 1;
    return $matches[0];
}

sub run {
    my (@command) = @_;
    print '+ ', join(' ', map { quote($_) } @command), "\n";
    system @command;
    die "Command failed (exit " . ($? >> 8) . "): $command[0]\n" if $? != 0;
}

sub succeeds {
    my (@command) = @_;
    print '+ ', join(' ', map { quote($_) } @command), "\n";
    system @command;
    return $? == 0;
}

sub capture {
    my (@command) = @_;
    open my $pipe, '-|', @command or die "Cannot run $command[0]: $!\n";
    local $/;
    my $output = <$pipe> // '';
    close $pipe or return '';
    return $output;
}

sub capture_line {
    my (@command) = @_;
    my $value = capture(@command);
    $value =~ s/\s+\z//;
    return $value;
}

sub quote {
    my ($value) = @_;
    return $value if $value =~ /\A[-A-Za-z0-9_.\/:\@]+\z/;
    $value =~ s/'/'"'"'/g;
    return "'$value'";
}

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: perl dev/import-perl5/update_perl5.pl [options]
  --perl-root PATH   checkout path (default: ./perl5)
  --repository URL   expected upstream repository
  --sync-script PATH sync implementation (default: dev/import-perl5/sync.pl)
  --sync             run the complete import manifest after updating
  --filter TEXT      pass exactly one --only filter to sync.pl (requires --sync)
  --verify-idempotent run sync twice and reject any second-pass output change

Existing checkouts must be on the branch advertised as the repository's HEAD.
The helper never switches branches; it refuses non-default branches instead.
USAGE
    exit $status;
}
