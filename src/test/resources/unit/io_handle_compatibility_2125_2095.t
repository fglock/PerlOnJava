use strict;
use warnings;
use Fcntl qw(O_CREAT O_TRUNC O_WRONLY O_RDONLY);
use File::Temp qw(tempfile);
use Errno qw(ENOENT);
use DirHandle;
use Test::More;

my ($seed, $path) = tempfile();
print {$seed} 'preserve me' or die "write seed: $!";
close $seed or die "close seed: $!";

ok(sysopen(my $writer, $path, O_CREAT | O_WRONLY),
    'sysopen opens an existing file without O_TRUNC');
close $writer or die "close writer: $!";
open my $reader, '<', $path or die "read preserved file: $!";
is(do { local $/; <$reader> }, 'preserve me',
    'sysopen preserves existing contents without O_TRUNC');
close $reader or die "close reader: $!";

ok(sysopen($writer, $path, O_CREAT | O_WRONLY | O_TRUNC),
    'sysopen opens an existing file with O_TRUNC');
close $writer or die "close truncating writer: $!";
ok(-z $path, 'O_TRUNC truncates the existing file');

my $undefined_open;
local $! = 0;
{
    local $SIG{__WARN__} = sub { };
    $undefined_open = open my $undefined_fh, undef;
}
ok(!defined($undefined_open), 'open with an undefined path fails');
is(0 + $!, ENOENT, 'open with an undefined path sets ENOENT');

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $dh = DirHandle->new('.');
    if ($dh) {
        $dh->close;
        undef $dh;
    }
}
is(scalar(grep { /closedir\(\) attempted on handle .* opened with open/ } @warnings), 0,
    'explicitly closed DirHandle destruction emits no spurious closedir warning');

unlink $path or die "unlink $path: $!";
done_testing();
