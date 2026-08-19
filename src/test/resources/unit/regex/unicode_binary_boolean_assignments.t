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

for my $true_value (qw(Y Yes T True)) {
    check_property('p', "Pattern_Syntax=$true_value", '+', 'A',
        "true alias $true_value");
}

for my $false_value (qw(N No F False)) {
    check_property('p', "Pattern_Syntax=$false_value", 'A', '+',
        "false alias $false_value");
}

check_property('p', 'Pattern Syntax : y', '+', 'A',
    'loose property and colon value');
check_property('p', 'Is_PatSyn = yes', '+', 'A',
    'Is-prefixed short property alias');
check_property('p', 'Variation Selector : n', 'A', chr(0xFE0F),
    'loose false property value');
check_property('p', 'Regional_Indicator = F', 'A', chr(0x1F1E6),
    'false short value');

check_property('P', 'Pattern_Syntax=Yes', 'A', '+',
    'outer P negates true assignment');
check_property('P', 'Pattern_Syntax=No', '+', 'A',
    'outer P negates false assignment');

my ($upper_true_i, $upper_true_i_error) =
    compile_property('p', 'Uppercase=Yes', 'iu');
ok(defined $upper_true_i, 'true assignment compiles under /i')
    or diag($upper_true_i_error);
like('A', $upper_true_i, 'true assignment matches uppercase under /i');
like('a', $upper_true_i, 'true assignment includes its lowercase fold under /i');
unlike('1', $upper_true_i, 'true assignment excludes an unrelated value under /i');

my ($upper_false_i, $upper_false_i_error) =
    compile_property('p', 'Uppercase=No', 'iu');
ok(defined $upper_false_i, 'false assignment compiles under /i')
    or diag($upper_false_i_error);
unlike('A', $upper_false_i, 'false assignment excludes uppercase under /i');
unlike('a', $upper_false_i, 'false assignment excludes its lowercase fold under /i');
like('1', $upper_false_i, 'false assignment includes an unrelated value under /i');

my $byte_a = pack('C', 0x41);
my $unicode_a = chr(0x41);
utf8::upgrade($unicode_a);
my ($uppercase, $uppercase_error) = compile_property('p', 'Uppercase=T');
ok(defined $uppercase, 'byte/Unicode true control compiles')
    or diag($uppercase_error);
like($byte_a, $uppercase, 'true assignment matches byte input');
like($unicode_a, $uppercase, 'true assignment matches upgraded input');

my $byte_ff = pack('C', 0xFF);
my $unicode_ff = chr(0xFF);
utf8::upgrade($unicode_ff);
my ($not_pattern_syntax, $not_pattern_syntax_error) =
    compile_property('p', 'Pattern_Syntax=False');
ok(defined $not_pattern_syntax, 'byte/Unicode false control compiles')
    or diag($not_pattern_syntax_error);
like($byte_ff, $not_pattern_syntax, 'false assignment matches byte input');
like($unicode_ff, $not_pattern_syntax,
    'false assignment matches upgraded input');

my @wide = (chr(0x110000), chr(0x7FFFFFFF), chr(hex('7FFFFFFFFFFFFFFF')));
my ($upper_true, $upper_true_error) = compile_property('p', 'Uppercase=Yes');
my ($upper_false, $upper_false_error) = compile_property('p', 'Uppercase=No');
my ($not_upper_true, $not_upper_true_error) =
    compile_property('P', 'Uppercase=Yes');
ok(defined($upper_true) && defined($upper_false) && defined($not_upper_true),
    'signed-wide controls compile')
    or diag($upper_true_error . $upper_false_error . $not_upper_true_error);
{
    local $SIG{__WARN__} = sub { };
    for my $index (0 .. $#wide) {
        unlike($wide[$index], $upper_true,
            "true assignment excludes signed-wide control $index");
        like($wide[$index], $upper_false,
            "false assignment includes signed-wide control $index");
        like($wide[$index], $not_upper_true,
            "outer P includes signed-wide control $index");
    }
}

our $callback_calls = 0;
sub IsBinaryBooleanCallback {
    $callback_calls++;
    return '0041';
}
my ($callback, $callback_error) =
    compile_property('p', 'IsBinaryBooleanCallback');
ok(defined $callback, 'exact user property callback compiles')
    or diag($callback_error);
like('A', $callback, 'exact user property callback matches its range');
is($callback_calls, 1, 'exact user property callback is called once');

my ($callback_assignment, $callback_assignment_error) =
    compile_property('p', 'IsBinaryBooleanCallback=Yes');
ok(!defined($callback_assignment) && length($callback_assignment_error),
    'boolean suffix does not broaden user-property lookup');
is($callback_calls, 1, 'rejected callback assignment is not invoked');

for my $rejected (
    'Uppercase=0',
    'Uppercase=1',
    'Uppercase=On',
    'Uppercase=Off',
    'Uppercase=Maybe',
    'Uppercase=',
    'NoSuchBinaryProperty=Yes',
    'General_Category=Yes',
    'Pattern_Syntax=: \A(?:Y|N)\z',
) {
    my ($pattern, $error) = compile_property('p', $rejected);
    ok(!defined($pattern) && length($error), "$rejected remains rejected");
}

done_testing;
