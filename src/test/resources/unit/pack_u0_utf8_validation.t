use strict;
use warnings;
use Test::More tests => 20;
use utf8 ();

sub bytes_of {
    my ($value) = @_;
    utf8::encode($value) if utf8::is_utf8($value);
    return unpack 'H*', $value;
}

{
    my $packed = pack 'U0C2', 0xc2, 0xa2;
    ok(utf8::is_utf8($packed), 'U0 upgrades valid packed UTF-8 bytes');
    is(length($packed), 1, 'valid two-byte UTF-8 is one character');
    is(ord($packed), 0xa2, 'valid two-byte UTF-8 decodes to its code point');
    is(bytes_of($packed), 'c2a2', 'valid U0 bytes round-trip exactly');
}

{
    my $packed = pack 'U0C2', 0x41, 0x42;
    ok(utf8::is_utf8($packed), 'U0 upgrades an ASCII-only result');
    is($packed, 'AB', 'ASCII bytes retain their values after upgrading');
}

{
    my $packed = pack 'C U0C2', 0xff, 0xc2, 0xa2;
    ok(utf8::is_utf8($packed), 'U0 upgrades bytes emitted before the mode switch');
    is_deeply([unpack 'U*', $packed], [0xff, 0xa2],
              'pre-U0 bytes upgrade as Latin-1 while the U0 segment decodes');
}

{
    my $packed = pack 'U0C2 C0C', 0xc2, 0xa2, 0xff;
    ok(utf8::is_utf8($packed), 'a completed U0 segment keeps the result upgraded');
    is_deeply([unpack 'U*', $packed], [0xa2, 0xff],
              'C0 bytes after U0 upgrade as characters without UTF-8 re-decoding');
}

{
    my $packed = pack 'C2', 0xa2, 0xf8;
    ok(!utf8::is_utf8($packed), 'ordinary C packing remains a byte string');
    is(bytes_of($packed), 'a2f8', 'ordinary C packing preserves arbitrary bytes');
}

for my $case (
    ['unexpected continuation', [0xa2, 0xf8],
     qr/^Malformed UTF-8 character: \\xa2 \(unexpected continuation byte 0xa2, with no preceding start byte\) in pack at /],
    ['truncated sequence',       [0xc2, 0x41],
     qr/^Malformed UTF-8 character: \\xc2\\x41 \(unexpected non-continuation byte 0x41, immediately after start byte 0xc2; need 2 bytes, got 1\) in pack at /],
) {
    my ($label, $bytes, $warning_pattern) = @$case;
    my ($value, $warning, $error);
    {
        no warnings 'utf8';
        local $SIG{__WARN__} = sub { $warning .= join '', @_ };
        local $@;
        $value = eval { pack 'U0C2', @$bytes };
        $error = $@;
    }
    ok(!defined($value), "$label is rejected by pack U0");
    like($warning // '', $warning_pattern,
         "$label warning retains Perl's byte-level diagnosis");
    like($error, qr/^Malformed UTF-8 character \(fatal\) at /,
         "$label has Perl's fatal diagnostic");
    like($error, qr/ line \d+\.\n\z/,
         "$label diagnostic retains the Perl source location");
}
