use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);
use Fcntl qw(O_WRONLY O_CREAT O_TRUNC O_NOFOLLOW);

my $dir = tempdir(CLEANUP => 1);
my $target = "$dir/target";
my $link = "$dir/link";

open my $target_fh, '>', $target or die "open $target: $!";
print {$target_fh} 'unchanged';
close $target_fh;

if (!symlink($target, $link)) {
    plan skip_all => "symlinks unavailable: $!";
}

ok(!sysopen(my $fh, $link, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, 0600),
    'O_NOFOLLOW rejects a final symbolic link');

open my $read_fh, '<', $target or die "open $target: $!";
is(do { local $/; <$read_fh> }, 'unchanged',
    'failed sysopen does not clobber the link target');
close $read_fh;

done_testing;
