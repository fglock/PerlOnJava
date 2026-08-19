use strict;
use warnings;
use Test::More;

sub compile_property {
    my ($property, $flags) = @_;
    $flags //= 'u';
    my $pattern = eval 'qr/\A\p{' . $property . '}\z/' . $flags;
    return ($pattern, $@);
}

sub check_property {
    my ($property, $member, $nonmember, $label) = @_;
    my ($pattern, $error) = compile_property($property);
    ok(defined $pattern, "$label compiles") or diag($error);
    like($member, $pattern, "$label matches a member");
    unlike($nonmember, $pattern, "$label excludes a nonmember");
}

check_property('Unicode', 'A', chr(0x110000), 'Unicode base alias');
check_property('_ Is_unicode', chr(0x10FFFF), chr(0x110000),
    'Unicode leading-Is alias');

check_property('VertSpace', "\n", ' ', 'VertSpace base alias');
check_property('_ is_Vert_Space', chr(0x2028), 'A',
    'VertSpace leading-Is alias');

check_property('Word', '_', '-', 'Word base alias');
check_property('_ Is_word', chr(0x0301), ' ', 'Word leading-Is alias');

check_property('Title', chr(0x01C5), 'A', 'Title base alias');
check_property('_ is_Title', chr(0x01C5), 'a', 'Title leading-Is alias');
check_property('Titlecase', chr(0x01C5), 'A', 'Titlecase base alias');
check_property('__Is_title_case', chr(0x01C5), 'a',
    'Titlecase leading-Is alias');

check_property('CE', chr(0x0958), 'A', 'CE base alias');
check_property('_ is_CE', chr(0x0F43), 'A', 'CE leading-Is alias');
check_property('Composition_Exclusion', chr(0xFB1D), 'A',
    'Composition_Exclusion base alias');
check_property('- Is_composition exclusion', chr(0x1D15E), 'A',
    'Composition_Exclusion leading-Is alias');

check_property('HorizSpace', "\t", "\n", 'HorizSpace base alias');
check_property('_ Is_horiz_space', chr(0x3000), 'A',
    'HorizSpace leading-Is alias');

my $byte_nel = pack('C', 0x85);
my $unicode_nel = chr(0x85);
utf8::upgrade($unicode_nel);
my ($vertical, $vertical_error) = compile_property('VertSpace');
ok(defined $vertical, 'VertSpace byte/Unicode control compiles')
    or diag($vertical_error);
like($byte_nel, $vertical, 'VertSpace matches byte NEL');
like($unicode_nel, $vertical, 'VertSpace matches upgraded NEL');

my $byte_ff = pack('C', 0xFF);
my $unicode_ff = chr(0xFF);
utf8::upgrade($unicode_ff);
my ($unicode, $unicode_error) = compile_property('Unicode');
ok(defined $unicode, 'Unicode byte/Unicode control compiles')
    or diag($unicode_error);
like($byte_ff, $unicode, 'Unicode matches a byte character');
like($unicode_ff, $unicode, 'Unicode matches its upgraded character');

for my $case (
    ['Unicode', 'A'],
    ['VertSpace', "\n"],
    ['Word', 'a'],
    ['Title', chr(0x01C6)],
    ['Titlecase', chr(0x01C4)],
    ['CE', chr(0x0958)],
    ['Composition_Exclusion', chr(0xFB1D)],
    ['HorizSpace', "\t"],
) {
    my ($property, $member) = @$case;
    my ($folded, $folded_error) = compile_property($property, 'iu');
    ok(defined $folded, "$property compiles under /i") or diag($folded_error);
    like($member, $folded, "$property retains Perl fold policy under /i");
}

my $callback_calls = 0;
sub IsMissingBaseCallback {
    $callback_calls++;
    return "0041";
}
my ($callback, $callback_error) = compile_property('IsMissingBaseCallback');
ok(defined $callback, 'exact user property callback retains precedence')
    or diag($callback_error);
like('A', $callback, 'exact user property callback matches its range');
is($callback_calls, 1, 'exact user property callback is called once');

my ($leading_callback, $leading_callback_error) =
    compile_property('_ IsMissingBaseCallback');
ok(!defined($leading_callback) && length($leading_callback_error),
    'leading separators do not broaden user property lookup');
is($callback_calls, 1, 'rejected leading callback spelling is not invoked');

my ($unknown, $unknown_error) = compile_property('_ Is_NoSuchBaseAlias');
ok(!defined($unknown) && length($unknown_error),
    'unknown leading-Is alias remains rejected');

done_testing;
