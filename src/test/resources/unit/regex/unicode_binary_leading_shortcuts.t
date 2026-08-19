use strict;
use warnings;
use Test::More;

sub compile_property {
    my ($property) = @_;
    my $pattern = eval 'qr/\A\p{' . $property . '}\z/u';
    return ($pattern, $@);
}

my ($canonical, $canonical_error) = compile_property('IsUppercase');
ok(defined $canonical, 'canonical Is binary shortcut compiles')
    or diag($canonical_error);
like('A', $canonical, 'canonical Is binary shortcut matches a member');
unlike('a', $canonical, 'canonical Is binary shortcut excludes a nonmember');

my ($lowercase, $lowercase_error) = compile_property('isuppercase');
ok(defined $lowercase, 'binary shortcut accepts a lowercase Is prefix')
    or diag($lowercase_error);
like('A', $lowercase, 'lowercase Is binary shortcut matches a member');

my ($leading, $leading_error) = compile_property('__Is_uppercase');
ok(defined $leading, 'binary shortcut accepts leading loose separators')
    or diag($leading_error);
like('A', $leading, 'leading-loose binary shortcut matches a member');
unlike('a', $leading, 'leading-loose binary shortcut excludes a nonmember');

my ($hex, $hex_error) = compile_property('_ is_ASCII_HEX_DIGIT');
ok(defined $hex, 'short binary alias accepts fully loose Is spelling')
    or diag($hex_error);
like('F', $hex, 'loose binary alias matches its member');
unlike('G', $hex, 'loose binary alias excludes a nonmember');

my ($assigned, $assigned_error) = compile_property('- Is_Assigned');
ok(defined $assigned, 'another binary family accepts a leading-loose Is')
    or diag($assigned_error);
like('A', $assigned, 'Assigned shortcut matches an assigned character');
unlike(chr(0x0378), $assigned,
    'Assigned shortcut excludes an unassigned character');

my ($script, $script_error) = compile_property('_ is_Latn');
ok(defined $script, 'Script shortcut retains precedence') or diag($script_error);
like('A', $script, 'Script shortcut membership remains intact');
unlike(chr(0x03B1), $script, 'Script shortcut still excludes another script');

my ($category, $category_error) = compile_property('IsL');
ok(defined $category, 'General_Category retains precedence')
    or diag($category_error);
like(chr(0x03B1), $category,
    'General_Category shortcut still matches non-Latin letters');

my ($block, $block_error) = compile_property('_ Is_Basic_Latin');
ok(defined $block, 'Block shortcut retains precedence') or diag($block_error);
like('A', $block, 'Block shortcut membership remains intact');

my $callback_calls = 0;
sub IsBinaryShortcutCallback {
    $callback_calls++;
    return "0041";
}
my ($callback, $callback_error) = compile_property('IsBinaryShortcutCallback');
ok(defined $callback, 'exact user property callback retains precedence')
    or diag($callback_error);
like('A', $callback, 'exact user property callback matches its range');
is($callback_calls, 1, 'exact user property callback is called once');

my ($leading_callback, $leading_callback_error) =
    compile_property('_ IsBinaryShortcutCallback');
ok(!defined($leading_callback) && length($leading_callback_error),
    'leading separators do not broaden user property lookup');
is($callback_calls, 1, 'rejected leading callback spelling is not invoked');

my ($unknown, $unknown_error) =
    compile_property('_ Is_DefinitelyNotABinaryProperty');
ok(!defined($unknown) && length($unknown_error),
    'unknown leading-loose binary shortcut is rejected');

done_testing;
