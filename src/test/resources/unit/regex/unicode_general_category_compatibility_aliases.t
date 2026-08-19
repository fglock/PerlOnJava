use strict;
use warnings;
use Test::More;

sub compile_property {
    my ($marker, $property, $flags) = @_;
    $flags //= 'u';
    my $pattern = eval 'qr/\\A\\' . $marker . '{' . $property . '}\\z/' . $flags;
    return ($pattern, $@);
}

our $callback_calls = 0;
sub IsPunctuation {
    $callback_calls++;
    return '0041';
}

my ($callback, $callback_error) = compile_property('p', 'IsPunctuation');
ok(defined $callback, 'exact GC-compatible callback compiles') or diag($callback_error);
like('A', $callback, 'exact GC-compatible callback matches its returned range');
unlike('!', $callback, 'exact callback shadows the built-in Is spelling');
is($callback_calls, 1, 'exact GC-compatible callback is called once');

my @cases = (
    ['C', 0x0000, 0x0041, 'Other aggregate'],
    ['L', 0x0041, 0x0031, 'Letter aggregate short alias'],
    ['_l&', 0x0041, 0x02B0, 'loose Cased Letter compatibility alias'],
    ['Letter', 0x0041, 0x0031, 'Letter aggregate long alias'],
    ['Lu', 0x0041, 0x0061, 'Uppercase Letter'],
    ['Mc', 0x0903, 0x0300, 'Spacing Combining Mark'],
    ['Me', 0x20DD, 0x0300, 'Enclosing Mark'],
    ['NonspacingMark', 0x0300, 0x0903,
        'Nonspacing Mark GC spelling classified as Bidi'],
    ['Nl', 0x2160, 0x0031, 'Letter Number'],
    ['No', 0x00B2, 0x0031, 'Other Number'],
    ['Number', 0x0031, 0x0041, 'Number aggregate'],
    ['Pc', 0x005F, 0x002D, 'Connector Punctuation'],
    ['Pf', 0x00BB, 0x00AB, 'Final Punctuation'],
    ['Pi', 0x00AB, 0x00BB, 'Initial Punctuation short alias'],
    ['Ps', 0x0028, 0x0029, 'Open Punctuation'],
    ['ParagraphSeparator', 0x2029, 0x2028,
        'Paragraph Separator GC spelling classified as Bidi'],
    ['Punct', 0x0021, 0x0041, 'Punctuation aggregate short alias'],
    ['S', 0x0024, 0x0021, 'Symbol aggregate'],
    ['Sc', 0x0024, 0x002B, 'Currency Symbol'],
    ['Sk', 0x005E, 0x002B, 'Modifier Symbol'],
    ['Sm', 0x002B, 0x0024, 'Math Symbol'],
    ['_ initial_PUNCTUATION', 0x00AB, 0x00BB,
        'InitialPunctuation compatibility spelling'],
    ['_ private_Use', 0xE000, 0x0041, 'PrivateUse compatibility spelling'],
    ['_ Is_Private_Use', 0xF0000, 0x0041,
        'loose IsPrivateUse compatibility spelling'],
    [' _PUNCTUATION', 0x0021, 0x0041,
        'Punctuation compatibility spelling'],
    ['_ Is_Punctuation', 0x0021, 0x0041,
        'loose IsPunctuation compatibility spelling'],
);

for my $case (@cases) {
    my ($property, $member, $nonmember, $label) = @$case;
    my ($pattern, $error) = compile_property('p', $property);
    ok(defined $pattern, "$label compiles") or diag($error);
    like(chr($member), $pattern, "$label matches a member");
    unlike(chr($nonmember), $pattern, "$label excludes a nonmember");
}

my ($not_punct, $not_punct_error) = compile_property('P', 'Punct');
ok(defined $not_punct, 'outer P GC alias compiles') or diag($not_punct_error);
unlike('!', $not_punct, 'outer P excludes a GC member');
like('A', $not_punct, 'outer P includes a GC nonmember');

my ($folded, $folded_error) = compile_property('p', 'Lu', 'iu');
ok(defined $folded, 'GC alias compiles under /i') or diag($folded_error);
like('A', $folded, 'GC alias retains direct membership under /i');
like('a', $folded, 'GC alias receives Perl case closure under /i');

my ($punctuation, $punctuation_error) = compile_property('p', 'Punctuation');
ok(defined $punctuation, 'byte/Unicode GC control compiles')
    or diag($punctuation_error);
my $byte = '!';
my $upgraded = '!';
utf8::upgrade($upgraded);
like($byte, $punctuation, 'GC alias matches byte input');
like($upgraded, $punctuation, 'GC alias matches upgraded input');

for my $control (
    ['Jamo', chr(0x1100), 'block precedence remains intact'],
    ['VS', chr(0xE0100), 'binary Variation_Selector precedence remains intact'],
    ['IDC', 'A', 'binary ID_Continue precedence remains intact'],
    ['Latin', 'A', 'script precedence remains intact'],
) {
    my ($property, $member, $label) = @$control;
    my ($pattern, $error) = compile_property('p', $property);
    ok(defined $pattern, "$label compiles") or diag($error);
    like($member, $pattern, $label);
}

for my $unknown ('_ NoSuchCategory', '_ Is_NoSuchCategory') {
    my ($pattern, $error) = compile_property('p', $unknown);
    ok(!defined($pattern) && length($error), "$unknown remains rejected");
}

done_testing;
