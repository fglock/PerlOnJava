use strict;
use warnings;
use Test::More tests => 5;
use File::Temp qw(tempdir);
use File::Slurp qw(read_file write_file);
use Path::Class qw(file);

my $dir = tempdir(CLEANUP => 1);
my $path = file($dir, 'payload.txt');

my $write_ok = eval {
    write_file($path, { err_mode => 'carp' }, "payload\n");
    1;
};
ok($write_ok, 'File::Slurp writes to a Path::Class file object with err_mode carp');
is(read_file($path), "payload\n", 'the Path::Class file contains the written payload');

my @warnings;
my $failed_write;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $failed_write = write_file(file($dir, 'missing', 'unwritable.txt'),
        { err_mode => 'carp' }, 'payload');
}
ok(!defined $failed_write, 'a failed write returns undef with err_mode carp');
like(join('', @warnings), qr/write_file .* - sysopen:/,
    'File::Slurp reports a failed write with a string diagnostic');
unlike(join('', @warnings), qr/SCALAR\(/,
    'the Carp diagnostic is not a scalar reference stringification');
