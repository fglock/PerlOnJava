use strict;
use warnings;
no warnings qw(non_unicode portable);
use Test::More;

sub compile_property {
    my ($marker, $property, $flags) = @_;
    $flags //= 'u';
    my $pattern = eval 'qr/\A\\' . $marker . '{' . $property . '}\z/' . $flags;
    return ($pattern, $@);
}

sub check_property {
    my ($marker, $property, $member, $nonmember, $label, $flags) = @_;
    my ($pattern, $error) = compile_property($marker, $property, $flags);
    ok(defined $pattern, "$label compiles") or diag($error);
    like($member, $pattern, "$label matches its member");
    unlike($nonmember, $pattern, "$label excludes its nonmember");
}

my @canonical_values = qw(
    ALetter CR Double_Quote E_Base E_Base_GAZ E_Modifier Extend
    ExtendNumLet Format Glue_After_Zwj Hebrew_Letter Katakana LF MidLetter
    MidNum MidNumLet Newline Numeric Other Regional_Indicator Single_Quote
    WSegSpace ZWJ
);
for my $value (@canonical_values) {
    my ($pattern, $error) = compile_property('p', "Word_Break=$value");
    ok(defined $pattern, "Word_Break=$value compiles") or diag($error);
}

check_property('p', 'WB=LE', 'A', '5', 'short key and ALetter value alias');
check_property('p', 'Word_Break=ALetter', 'z', '5', 'long ALetter aliases');
check_property('p', 'WB=NU', '5', 'A', 'numeric value alias');
check_property('p', 'WB=KA', chr(0x30A2), 'A', 'Katakana value alias');
check_property('p', 'WB=HL', chr(0x05D0), 'A', 'Hebrew Letter value alias');
check_property('p', 'WB=RI', chr(0x1F1E6), 'A', 'regional indicator value alias');
check_property('p', 'WB=ZWJ', chr(0x200D), 'A', 'ZWJ value');
check_property('p', 'WB=WSegSpace', chr(0x2000), 'A', 'WSegSpace value');
check_property('p', 'WB=DQ', '"', 'A', 'double quote value alias');
check_property('p', 'WB=SQ', "'", 'A', 'single quote value alias');
check_property('p', 'WB=CR', "\r", "\n", 'CR value');
check_property('p', 'WB=LF', "\n", "\r", 'LF value');
check_property('p', 'Word Break : Hebrew Letter', chr(0x05D0), 'A',
    'loose long key and value with colon');

check_property('P', 'WB=LE', '5', 'A', 'outer P negates Word_Break');
check_property('p', '^WB=LE', '5', 'A', 'inner caret negates Word_Break');

my ($aletter_i, $aletter_i_error) = compile_property('p', 'WB=LE', 'iu');
ok(defined $aletter_i, 'Word_Break compiles under /i') or diag($aletter_i_error);
like('A', $aletter_i, 'Word_Break matches uppercase ALetter under /i');
like('a', $aletter_i, 'Word_Break matches lowercase ALetter under /i');
unlike('5', $aletter_i, 'Word_Break excludes Numeric under /i');

my $byte_a = pack('C', 0x41);
my $unicode_a = chr(0x41);
utf8::upgrade($unicode_a);
my ($aletter, $aletter_error) = compile_property('p', 'WB=LE');
ok(defined $aletter, 'byte/Unicode Word_Break control compiles')
    or diag($aletter_error);
like($byte_a, $aletter, 'Word_Break matches byte input');
like($unicode_a, $aletter, 'Word_Break matches upgraded input');

my @wide = (chr(0x110000), chr(0x7FFFFFFF), chr(hex('7FFFFFFFFFFFFFFF')));
my ($not_aletter, $not_aletter_error) = compile_property('P', 'WB=LE');
ok(defined($aletter) && defined($not_aletter), 'signed-wide controls compile')
    or diag($aletter_error . $not_aletter_error);
{
    local $SIG{__WARN__} = sub { };
    for my $index (0 .. $#wide) {
        unlike($wide[$index], $aletter,
            "Word_Break excludes signed-wide control $index");
        like($wide[$index], $not_aletter,
            "outer P includes signed-wide control $index");
    }
}

our $callback_calls = 0;
sub IsWordBreakAliasCallback {
    $callback_calls++;
    return '0041';
}
my ($callback, $callback_error) =
    compile_property('p', 'IsWordBreakAliasCallback');
ok(defined $callback, 'user property callback compiles') or diag($callback_error);
like('A', $callback, 'user property callback matches its range');
is($callback_calls, 1, 'user property callback is called once');

for my $rejected (
    'WB=',
    'WB=True',
    'WB=ATerm',
    'WB=NoSuchValue',
    'NoSuchWordBreak=ALetter',
    'ALetter',
    'Word_Break=: \A(?:ALetter|Numeric)\z',
    'Word_Break=Yes',
) {
    my ($pattern, $error) = compile_property('p', $rejected);
    ok(!defined($pattern) && length($error), "$rejected remains rejected");
}

done_testing;
