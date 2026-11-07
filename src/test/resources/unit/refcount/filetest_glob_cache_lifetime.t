use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

our $destroyed = 0;

{
    package FileTestCacheLifetimeHandle;

    sub DESTROY {
        my ($self) = @_;
        close $self;
        $main::destroyed++;
    }
}

my ($handle, $path) = tempfile();
bless $handle, 'FileTestCacheLifetimeHandle';
ok(-e $handle, 'file test accepts a blessed glob handle');
undef $handle;
is($destroyed, 1, 'file-test cache does not retain the blessed glob');
unlink $path or die "unlink $path: $!";

$destroyed = 0;
my ($stat_handle, $stat_path) = tempfile();
bless $stat_handle, 'FileTestCacheLifetimeHandle';
my @stat = stat($stat_handle);
is(scalar(@stat), 13, 'stat accepts a blessed glob handle');
undef $stat_handle;
is($destroyed, 1, 'stat cache does not retain the blessed glob');
unlink $stat_path or die "unlink $stat_path: $!";

done_testing;
