use strict;
use warnings;
use charnames qw(:full);
use Test::More tests => 8;

{
    use utf8;
    use feature 'unicode_eval';
    my $source = '$' . "\N{POUND SIGN}" . ' = 1';
    local $SIG{__WARN__} = sub { };
    my $value = eval $source;
    like($@, qr/Unrecognized character \\x\{a3\}/,
        'unicode_eval treats a Latin-1 byte as a character, not malformed UTF-8');
    ok(!defined $value, 'invalid Latin-1 source still fails compilation');
}

{
    use utf8;
    use feature 'unicode_eval';
    my $source = pack('C*', 0x22, 0xC3, 0xB7, 0x22);
    my $value = eval $source;
    is(ord($value), 0xC3,
        'unicode_eval prevents the inherited utf8 hint from decoding byte source');
}

{
    use utf8;
    no feature 'unicode_eval';
    my $source = pack('C*', 0x22, 0xC3, 0xB7, 0x22);
    my $value = eval $source;
    is($@, '', 'ordinary byte eval accepts valid UTF-8 under inherited use utf8');
    is(ord($value), 0xF7, 'ordinary byte eval decodes valid UTF-8');
}

{
    use utf8;
    no feature 'unicode_eval';
    my $delimiter = pack('C*', 0xFE, 0x82, 0x80, 0x80, 0x80, 0x80, 0x80);
    my $source = '$value = q ' . $delimiter . 'abc' . $delimiter;
    our $value;
    eval $source;
    is($@, '', 'ordinary byte eval accepts a legacy extended UTF-8 delimiter');
    is($value, 'abc', 'legacy extended UTF-8 delimiter parses correctly');
}

{
    use utf8;
    no feature 'unicode_eval';
    my $source = pack('C*', 0x22, 0xFF, 0x22);
    local $SIG{__WARN__} = sub { };
    my $value = eval $source;
    ok(!defined($value) && $@ =~ /Malformed UTF-8 character/,
        'malformed UTF-8 byte source remains fatal');
}
