use strict;
use warnings;
use Test::More;

sub scalar_with_mode {
    my ($text, $unicode) = @_;
    my $copy = $text;
    if ($unicode) {
        utf8::upgrade($copy);
    }
    else {
        utf8::downgrade($copy, 1)
            or die "fixture cannot be byte backed";
    }
    return $copy;
}

sub pattern_with_mode {
    my ($source, $unicode, $class) = @_;
    my $copy = scalar_with_mode($source, $unicode);
    return $class ? qr/[$copy]/di : qr/$copy/di;
}

my @modes = (
    [0, 'byte'],
    [1, 'unicode'],
);

my $upper = chr 0xC0;
my $lower = chr 0xE0;

for my $pattern_mode (@modes) {
    for my $subject_mode (@modes) {
        my ($pattern_unicode, $pattern_name) = @$pattern_mode;
        my ($subject_unicode, $subject_name) = @$subject_mode;
        my $label = "$pattern_name pattern / $subject_name subject";

        my $literal = pattern_with_mode($lower, $pattern_unicode, 0);
        my $class = pattern_with_mode($lower, $pattern_unicode, 1);
        my $subject = scalar_with_mode($upper, $subject_unicode);
        my $unicode_fold = $pattern_unicode || $subject_unicode;

        is(($subject =~ $literal) ? 1 : 0, $unicode_fold ? 1 : 0,
            "default d literal simple fold: $label");
        is(($subject =~ $class) ? 1 : 0, $unicode_fold ? 1 : 0,
            "default d class simple fold: $label");

        my $sharp = pattern_with_mode(chr(0xDF), $pattern_unicode, 0);
        my $ss = scalar_with_mode('ss', $subject_unicode);
        is(($ss =~ $sharp) ? 1 : 0, $unicode_fold ? 1 : 0,
            "default d literal full fold: $label");

        my $property_source = scalar_with_mode('\\p{Lowercase}',
                                                $pattern_unicode);
        my $property = qr/$property_source/di;
        ok($subject =~ $property,
            "Unicode property promotes folding: $label");

        my $exact_source = scalar_with_mode($upper, $pattern_unicode);
        my $exact = qr/($exact_source)/d;
        my $framed = scalar_with_mode("X${upper}Y", $subject_unicode);
        ok($framed =~ $exact, "exact capture matches: $label");
        is($1, $upper, "capture value is stable: $label");
        is($-[1], 1, "capture start is a character offset: $label");
        is($+[1], 2, "capture end is a character offset: $label");

        ok($framed =~ $exact,
            "compiled qr reuse preserves provenance: $label");
    }
}

for my $pattern_mode (@modes) {
    my ($pattern_unicode, $pattern_name) = @$pattern_mode;
    for my $escape (['numeric', '\\x{100}'], ['named', '\\N{U+0100}']) {
        my ($escape_name, $source) = @$escape;
        my $pattern_source = scalar_with_mode($source, $pattern_unicode);
        my $pattern = qr/$pattern_source/d;
        my $byte_subject = pack('C*', 0xC4, 0x80);
        my $unicode_subject = chr 0x100;
        utf8::upgrade($unicode_subject);

        ok($byte_subject !~ $pattern,
            "$escape_name wide escape does not reinterpret ordinary octets: $pattern_name pattern");
        ok($unicode_subject =~ $pattern,
            "$escape_name wide escape promotes Unicode subject: $pattern_name pattern");
    }
}

my $byte_target = pack('C*', 0x41, 0xC3, 0x80, 0x42);
my $unicode_pattern = chr 0xC0;
utf8::upgrade($unicode_pattern);
{
    use bytes;
    is($byte_target =~ s/$unicode_pattern/Z/d, 1,
        'use bytes substitution matches a Unicode pattern as encoded octets');
}
is(unpack('H*', $byte_target), '415a42',
    'use bytes substitution consumes the complete encoded scalar');
ok(!utf8::is_utf8($byte_target),
    'use bytes substitution preserves byte target storage');

my $ordinary_target = scalar_with_mode("A${upper}B", 1);
my $byte_pattern_source = scalar_with_mode($upper, 0);
my $byte_qr = qr/$byte_pattern_source/d;
is($ordinary_target =~ s/$byte_qr/Q/, 1,
    'ordinary substitution promotes a byte qr against a Unicode subject');
is($ordinary_target, 'AQB',
    'ordinary substitution keeps character accounting after promotion');
ok(utf8::is_utf8($ordinary_target),
    'ordinary substitution retains Unicode target storage');

done_testing;
