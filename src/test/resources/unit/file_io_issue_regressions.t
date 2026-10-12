use strict;
use warnings;
use utf8;
use Encode qw(encode_utf8);
use Fcntl qw(O_CREAT O_EXCL O_RDONLY);
use File::Temp qw(tempfile);
use Test::More;

my ($seed, $path) = tempfile();
close $seed or die "close temporary file: $!";

unlink $path or die "unlink temporary seed file: $!";
my $created_path = $path;
my $created = sysopen(my $created_fh, $created_path, O_CREAT | O_EXCL);
ok($created, 'sysopen creates a file when access flags are omitted');
if ($created) {
    my $write_result;
    {
        local $SIG{__WARN__} = sub { };
        $write_result = syswrite($created_fh, 'x');
    }
    ok(!defined($write_result), 'omitted sysopen access mode retains read-only access');
    close $created_fh or die "close created file: $!";
    ok(!sysopen(my $collision, $created_path, O_CREAT | O_EXCL),
        'O_EXCL rejects a second create of the same path');
    ok(sysopen(my $read_fh, $created_path, O_RDONLY), 'created file can be reopened for reading');
    close $read_fh or die "close read handle: $!";
} else {
    pass('read-only access check requires a created file');
    pass('O_EXCL collision check requires a created file');
    pass('read-only open check requires a created file');
    pass('read reopen check requires a created file');
}
unlink $created_path if -e $created_path;

my $scalar = 'scalar-backed content';
open my $scalar_fh, '+<', \$scalar or die "open scalar handle: $!";
my $scalar_truncate = truncate($scalar_fh, 6);
ok(!defined($scalar_truncate), 'truncate on a scalar-backed filehandle reports unsupported');
is($scalar, 'scalar-backed content', 'failed scalar-handle truncate leaves the scalar unchanged');
close $scalar_fh or die "close scalar handle: $!";

my ($utf8_out, $utf8_path) = tempfile();
binmode $utf8_out, ':utf8' or die "binmode UTF-8 output: $!";
my @rows = map { "\x{00e9}word$_\n" } 0 .. 799;
print {$utf8_out} @rows or die "write UTF-8 rows: $!";
close $utf8_out or die "close UTF-8 output: $!";

open my $utf8_in, '<:utf8', $utf8_path or die "open UTF-8 input: $!";
my @offsets = (0);
for my $index (0 .. $#rows) {
    $offsets[$index + 1] = $offsets[$index] + length encode_utf8($rows[$index]);
}
for my $index (0, 100, 200, 276) {
    # Start partway through the ASCII portion of each row, as a byte-oriented
    # binary search does before discarding the partial line.
    my $offset = $offsets[$index] + 6;
    seek $utf8_in, $offset, 0 or die "seek to UTF-8 row: $!";
    my $got = <$utf8_in>;
    is($got, substr($rows[$index], 5), "seek by byte offset reads UTF-8 row suffix $index");
    is(tell($utf8_in), $offsets[$index + 1],
        "tell reports byte position after UTF-8 row $index");
}
close $utf8_in or die "close UTF-8 input: $!";
unlink $utf8_path or die "unlink $utf8_path: $!";
done_testing();
