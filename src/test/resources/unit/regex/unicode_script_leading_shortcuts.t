use strict;
use warnings;
use Test::More;

sub compile_property {
    my ($property) = @_;
    my $pattern = eval 'qr/\A\p{' . $property . '}\z/u';
    return ($pattern, $@);
}

my ($canonical, $canonical_error) = compile_property('Is_Latin');
ok(defined $canonical, 'canonical Is Script shortcut compiles')
    or diag($canonical_error);
like('A', $canonical, 'canonical Is Script shortcut matches its script');
unlike(chr(0x03B1), $canonical,
    'canonical Is Script shortcut excludes another script');

my ($lowercase, $lowercase_error) = compile_property('islatin');
ok(defined $lowercase, 'Script shortcut accepts a lowercase Is prefix')
    or diag($lowercase_error);
like('A', $lowercase, 'lowercase Is Script shortcut matches its script');

my ($leading, $leading_error) = compile_property(' -IS_LATN');
ok(defined $leading, 'Script shortcut accepts leading loose separators')
    or diag($leading_error);
like('A', $leading, 'leading-loose Script alias matches its script');
unlike(chr(0x03B1), $leading,
    'leading-loose Script alias excludes another script');

my ($extensions, $extensions_error) = compile_property('_ is_hira');
ok(defined $extensions, 'short Script alias accepts loose Is spelling')
    or diag($extensions_error);
like(chr(0x30FC), $extensions,
    'bare Script shortcut retains Script_Extensions membership');

my ($binary, $binary_error) = compile_property('IsUppercase');
ok(defined $binary, 'binary property retains precedence') or diag($binary_error);
like('A', $binary, 'binary property membership remains intact');

my ($category, $category_error) = compile_property('IsL');
ok(defined $category, 'General_Category retains precedence')
    or diag($category_error);
like(chr(0x03B1), $category,
    'General_Category shortcut still matches non-Latin letters');

my $callback_calls = 0;
sub IsScriptShortcutCallback {
    $callback_calls++;
    return "0041";
}
my ($callback, $callback_error) =
    compile_property('IsScriptShortcutCallback');
ok(defined $callback, 'exact user property callback retains precedence')
    or diag($callback_error);
like('A', $callback, 'exact user property callback matches its range');
is($callback_calls, 1, 'exact user property callback is called once');

my ($leading_callback, $leading_callback_error) =
    compile_property('_ IsScriptShortcutCallback');
ok(!defined($leading_callback) && length($leading_callback_error),
    'leading separators do not broaden user property lookup');
is($callback_calls, 1, 'rejected leading callback spelling is not invoked');

my ($hrkt, $hrkt_error) = compile_property('_ Is_Hrkt');
ok(!defined($hrkt) && length($hrkt_error),
    'Katakana_Or_Hiragana Script shortcut remains rejected');

my ($unknown, $unknown_error) = compile_property('_ Is_DefinitelyNotAScript');
ok(!defined($unknown) && length($unknown_error),
    'unknown leading-loose Script shortcut is rejected');

done_testing;
