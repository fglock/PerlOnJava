use strict;
use warnings;
use Test::More tests => 4;
use Fcntl qw(O_CREAT O_EXCL O_RDONLY O_RDWR);
use File::Spec;

my $path = File::Spec->catfile(
    File::Spec->tmpdir,
    join('-', 'perlonjava-sysopen-readonly', $$, time, int(rand(1_000_000))),
);

END {
    chmod 0600, $path if defined $path && -e $path;
    unlink $path if defined $path && -e $path;
}

my $old_umask = umask 0;
my $opened = sysopen(my $fh, $path, O_RDWR | O_CREAT | O_EXCL, 0400);
my $open_error = "$!";
umask $old_umask;

ok($opened, 'sysopen atomically creates a read-only file with a writable handle')
    or diag("sysopen failed: $open_error");

SKIP: {
    skip 'sysopen did not return a handle', 3 unless $opened;

    is((stat $path)[2] & 0777, 0400, 'creation permissions are applied');
    is(syswrite($fh, 'content'), 7, 'the creating descriptor remains writable');
    close $fh;

    sysopen(my $read_fh, $path, O_RDONLY) or die "reopen $path: $!";
    my $content = '';
    sysread($read_fh, $content, 7);
    close $read_fh;
    is($content, 'content', 'the data written through the creating descriptor persists');
}
