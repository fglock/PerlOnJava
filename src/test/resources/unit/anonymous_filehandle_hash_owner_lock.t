use strict;
use warnings;
use Test::More tests => 2;
use File::Temp qw(tempdir);
use Fcntl qw(:flock);

my $directory = tempdir(CLEANUP => 1);
my $path = "$directory/locked-file";
my $other_path = "$directory/other-file";
for my $file ($path, $other_path) {
    open my $created, '>', $file or die "create $file: $!";
    close $created or die "close $file: $!";
}

{
    package AnonymousFileLockHolder;
    use Fcntl qw(:flock);

    sub new {
        my ($class, $file) = @_;
        open my $fh, '<', $file or die "open $file: $!";
        flock($fh, LOCK_SH | LOCK_NB) or die "lock $file: $!";
        return bless { fh => $fh }, $class;
    }
}

sub lock_is_available {
    open my $probe, '>>', $path or die "open probe $path: $!";
    my $available = flock($probe, LOCK_EX | LOCK_NB);
    close $probe;
    return $available;
}

my %holder = (
    read => AnonymousFileLockHolder->new($path),
    other => AnonymousFileLockHolder->new($other_path),
);
ok(!lock_is_available(), 'later constructor leaves the hash-held file lock alive');

delete $holder{read};
ok(lock_is_available(), 'releasing the final hash-held object releases its file lock');
