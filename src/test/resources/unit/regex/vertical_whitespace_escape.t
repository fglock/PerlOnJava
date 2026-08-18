use strict;
use warnings;
use feature 'unicode_strings';
use Test::More;

my @vertical = (0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029);
my @other = (0x00, 0x09, 0x20, 0x56, 0xA0, 0x100);

sub check_code_point {
    my ($code, $expected, $upgraded) = @_;
    my $character = chr($code);
    utf8::upgrade($character) if $upgraded;
    my $mode = $upgraded ? 'upgraded' : 'native';
    my $label = sprintf 'U+%04X %s', $code, $mode;

    is($character =~ /\v/ ? 1 : 0, $expected, "direct v $label");
    is($character =~ /\V/ ? 1 : 0, 1 - $expected, "direct V $label");
    is($character =~ /[\v]/ ? 1 : 0, $expected, "class v $label");
    is($character =~ /[\V]/ ? 1 : 0, 1 - $expected, "class V $label");
}

for my $code (@vertical) {
    check_code_point($code, 1, 0);
    check_code_point($code, 1, 1) if $code <= 0xFF;
}
for my $code (@other) {
    check_code_point($code, 0, 0);
    check_code_point($code, 0, 1) if $code <= 0xFF;
}

done_testing();
