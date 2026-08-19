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
sub IsJamo {
    $callback_calls++;
    return '0041';
}

my ($callback, $callback_error) = compile_property('p', 'IsJamo');
ok(defined $callback, 'exact block-name callback compiles') or diag($callback_error);
like('A', $callback, 'exact block-name callback matches its returned range');
unlike(chr(0x1100), $callback, 'exact callback shadows the built-in Is spelling');
is($callback_calls, 1, 'exact block-name callback is called once');

my @cases = (
    ['_ indic_number_forms', 0xA830, 0xA800, 'In Common Indic Number Forms'],
    ['- indic_siyaq_numbers', 0x1EC71, 0x1EC00, 'In Indic Siyaq Numbers'],
    ['Jamo', 0x1100, 0x3131, 'bare Jamo block alias'],
    ['_ Is_Jamo', 0x1100, 0x3131, 'loose Is Jamo block alias'],
);

for my $case (@cases) {
    my ($property, $member, $nonmember, $label) = @$case;
    my ($pattern, $error) = compile_property('p', $property);
    ok(defined $pattern, "$label compiles") or diag($error);
    like(chr($member), $pattern, "$label matches a member");
    unlike(chr($nonmember), $pattern, "$label excludes a nonmember");
}

my ($not_jamo, $not_jamo_error) = compile_property('P', 'Jamo');
ok(defined $not_jamo, 'outer P block shortcut compiles') or diag($not_jamo_error);
unlike(chr(0x1100), $not_jamo, 'outer P excludes a block member');
like(chr(0x3131), $not_jamo, 'outer P includes a block nonmember');

my ($folded, $folded_error) = compile_property('p', '- indic_siyaq_numbers', 'iu');
ok(defined $folded, 'block shortcut compiles under /i') or diag($folded_error);
like(chr(0x1EC71), $folded, 'block shortcut retains membership under /i');
unlike(chr(0x1EC00), $folded, 'block shortcut retains exclusion under /i');

my $byte = 'A';
my $upgraded = 'A';
utf8::upgrade($upgraded);
my ($jamo, $jamo_error) = compile_property('p', 'Jamo');
ok(defined $jamo, 'byte/Unicode block control compiles') or diag($jamo_error);
unlike($byte, $jamo, 'block shortcut excludes byte input');
unlike($upgraded, $jamo, 'block shortcut excludes upgraded input');

for my $unknown ('_ In_NoSuchBlock', '_ Is_NoSuchBlock') {
    my ($pattern, $error) = compile_property('p', $unknown);
    ok(!defined($pattern) && length($error), "$unknown remains rejected");
}

done_testing;
