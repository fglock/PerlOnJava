use strict;
use warnings;
use utf8;
use Test::More tests => 5;

sub preserve_wide_string_after_bytes_block {
    my $wide = "\x{20ac}";
    {
        use bytes;
        is(length($wide), 3, 'nested bytes scope sees UTF-8 octets');
    }

    my $copy = $wide . '';
    return $copy;
}

my $direct = preserve_wide_string_after_bytes_block();
ok(utf8::is_utf8($direct), 'outer scope restores the UTF-8 flag for concatenation');
is(ord($direct), 0x20ac, 'outer concatenation preserves the Unicode character');

my $from_eval = eval q{
    use utf8;
    my $wide = "\x{20ac}";
    { use bytes; my $octets = length($wide) }
    $wide . '';
};
is($@, '', 'interpreter-backed eval compiles successfully');
is(ord($from_eval), 0x20ac, 'interpreter-backed eval restores outer lexical hints');
