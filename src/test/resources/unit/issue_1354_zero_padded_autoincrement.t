use strict;
use warnings;
use Test::More;

my $expected = '01';
for my $month (1 .. 12) {
    is($expected, sprintf('%02d', $month), "increment preserves month padding at $month");
    ++$expected;
}
my $carry = '009';
++$carry;
is($carry, '010', 'increment preserves width across a carry');
my $all_zeroes = '000';
++ $all_zeroes;
is($all_zeroes, '001', 'increment preserves width when all digits are zero');
my $full_carry = '099';
++ $full_carry;
is($full_carry, '100', 'increment grows the width after the final digit rolls over');
my $byte_string = '009';
ok(!utf8::is_utf8($byte_string), 'plain padded string starts without the UTF-8 flag');
++ $byte_string;
is($byte_string, '010', 'increment preserves padding on a plain string');
ok(!utf8::is_utf8($byte_string), 'increment keeps a plain string without the UTF-8 flag');
my $upgraded_string = '009';
utf8::upgrade($upgraded_string);
++ $upgraded_string;
is($upgraded_string, '010', 'increment preserves padding on an upgraded string');
ok(utf8::is_utf8($upgraded_string), 'increment keeps an upgraded string UTF-8 flagged');

done_testing();
