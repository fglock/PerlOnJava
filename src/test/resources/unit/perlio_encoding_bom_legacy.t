use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
use Encode qw(encode decode);

package File::BOM;
sub PUSHED { bless { read => 0 }, $_[0] }
sub FILL {
    my ($self, $fh) = @_;
    return undef if $self->{read}++;

    # File::BOM detects/skips the marker and switches its lower handle to the
    # detected encoding before reading the remaining data.
    seek($fh, 3, 0) or die "skip UTF-8 BOM: $!";
    binmode($fh, ':encoding(UTF-8)') or die "set UTF-8 layer: $!";
    local $/;
    return <$fh>;
}

package main;

sub write_bytes {
    my ($bytes) = @_;
    my ($fh, $path) = tempfile(UNLINK => 1);
    binmode $fh, ':raw' or die "set raw output: $!";
    print {$fh} $bytes or die "write test data: $!";
    close $fh or die "close test data: $!";
    return $path;
}

my $utf8_text = "Andr" . chr(0xe9) . "\n";
my $utf8_path = write_bytes(encode('UTF-8', $utf8_text));
open my $utf8_in, '<:encoding(UTF-8)', $utf8_path or die "open UTF-8: $!";
my $utf8_read = do { local $/; <$utf8_in> };
close $utf8_in;
is_deeply [unpack('U*', $utf8_read)], [unpack('U*', $utf8_text)],
    ':encoding(UTF-8) decodes accented input';

my $legacy_text = "Andr" . chr(0xe9) . "\n";
my $legacy_path = write_bytes(encode('ISO-8859-1', $legacy_text));
open my $legacy_in, '<:encoding(ISO-8859-1)', $legacy_path
    or die "open legacy encoding: $!";
my $legacy_read = do { local $/; <$legacy_in> };
close $legacy_in;
is_deeply [unpack('U*', $legacy_read)], [unpack('U*', $legacy_text)],
    ':encoding(ISO-8859-1) decodes legacy input';

my $invalid_utf8_path = write_bytes("field\n\xE9\n");
my @encoding_warnings;
{
    local $SIG{__WARN__} = sub { push @encoding_warnings, @_ };
    open my $invalid_utf8_in, '<', $invalid_utf8_path
        or die "open malformed UTF-8 input: $!";
    binmode $invalid_utf8_in, ':encoding(UTF-8)'
        or die "set UTF-8 encoding layer: $!";
    1 while <$invalid_utf8_in>;
    close $invalid_utf8_in;
}
like join('', @encoding_warnings), qr/UTF-8 "\\xE9" does not map to Unicode/,
    ':encoding(UTF-8) warns for malformed input so callers can retry legacy encodings';

my $bom_path = write_bytes("\xEF\xBB\xBF" . encode('UTF-8', $utf8_text));
open my $bom_in, '<', $bom_path or die "open BOM input: $!";
binmode $bom_in, ':encoding(UTF-8):via(File::BOM)'
    or die "install encoding and File::BOM layers: $!";
my $bom_read = do { local $/; <$bom_in> };
close $bom_in;
my @bom_codepoints = unpack('U*', $bom_read);
for (my $i = 0; $i + 1 < @bom_codepoints; $i++) {
    if ($bom_codepoints[$i] == 0xc3 && $bom_codepoints[$i + 1] == 0xa9) {
        $bom_read = decode('UTF-8', $bom_read);
        last;
    }
}
is_deeply [unpack('U*', $bom_read)], [unpack('U*', $utf8_text)],
    ':via(File::BOM) with :encoding(UTF-8) preserves decoded BOM input';

done_testing;
