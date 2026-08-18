use strict;
use warnings;
use feature 'unicode_strings';
use Test::More;

my @horizontal = (0x09, 0x20, 0xA0, 0x1680, 0x2000, 0x200A,
                  0x202F, 0x205F, 0x3000);
my @other = (0x0A, 0x0B, 0x41, 0x85, 0x180E, 0x2028, 0x2029);

sub check_code_point {
    my ($code, $expected, $upgraded) = @_;
    my $character = chr($code);
    if ($upgraded) {
        utf8::upgrade($character);
    }
    else {
        utf8::downgrade($character, 1)
            or die sprintf "U+%04X cannot be represented as bytes", $code;
    }
    my $mode = $upgraded ? 'Unicode' : 'byte';
    my $label = sprintf 'U+%04X %s', $code, $mode;

    is($character =~ /\A\h\z/ ? 1 : 0, $expected,
        "direct h $label");
    is($character =~ /\A\H\z/ ? 1 : 0, 1 - $expected,
        "direct H $label");
    is($character =~ /\A[\h]\z/ ? 1 : 0, $expected,
        "class h $label");
    is($character =~ /\A[\H]\z/ ? 1 : 0, 1 - $expected,
        "class H $label");
}

for my $code (@horizontal) {
    check_code_point($code, 1, 1);
    check_code_point($code, 1, 0) if $code <= 0xFF;
}
for my $code (@other) {
    check_code_point($code, 0, 1);
    check_code_point($code, 0, 0) if $code <= 0xFF;
}

my $horizontal_run = join '', map chr, @horizontal;
my $other_run = join '', map chr, @other;
ok($horizontal_run =~ /\A\h+\z/,
    'direct h matches a run of horizontal whitespace');
ok($horizontal_run =~ /\A[\h]+\z/,
    'class h matches a run of horizontal whitespace');
ok($other_run =~ /\A\H+\z/,
    'direct H matches a run without horizontal whitespace');
ok($other_run =~ /\A[\H]+\z/,
    'class H matches a run without horizontal whitespace');

my $ideographic_space = chr 0x3000;
my $line_feed = "\n";
ok($ideographic_space =~ /\A(?a:\h)\z/,
    'scoped a keeps direct h Unicode-aware');
ok($ideographic_space =~ /\A(?aa:\h)\z/,
    'scoped aa keeps direct h Unicode-aware');
ok($ideographic_space =~ /\A(?a:[\h])\z/,
    'scoped a keeps class h Unicode-aware');
ok($ideographic_space =~ /\A(?aa:[\h])\z/,
    'scoped aa keeps class h Unicode-aware');
ok($line_feed =~ /\A(?a:\H)\z/,
    'scoped a keeps direct H complement semantics');
ok($line_feed =~ /\A(?aa:\H)\z/,
    'scoped aa keeps direct H complement semantics');
ok($line_feed =~ /\A(?a:[\H])\z/,
    'scoped a keeps class H complement semantics');
ok($line_feed =~ /\A(?aa:[\H])\z/,
    'scoped aa keeps class H complement semantics');

my $byte_nbsp = chr 0xA0;
utf8::downgrade($byte_nbsp, 1);
ok($byte_nbsp =~ /\A(?a:\h)\z/,
    'scoped a keeps direct h byte semantics');
ok($byte_nbsp =~ /\A(?aa:\h)\z/,
    'scoped aa keeps direct h byte semantics');
ok($byte_nbsp =~ /\A(?a:[\h])\z/,
    'scoped a keeps class h byte semantics');
ok($byte_nbsp =~ /\A(?aa:[\h])\z/,
    'scoped aa keeps class h byte semantics');

done_testing();
