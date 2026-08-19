use strict;
use warnings;
use Test::More;

sub compile_property {
    my ($property) = @_;
    my $pattern = eval 'qr/\A\p{' . $property . '}\z/u';
    return ($pattern, $@);
}

my ($canonical, $canonical_error) = compile_property('In_Emoticons');
ok(defined $canonical, 'canonical In Block shortcut compiles')
    or diag($canonical_error);
like(chr(0x1F600), $canonical, 'canonical In Block shortcut matches its block');
unlike('A', $canonical, 'canonical In Block shortcut excludes another block');

my ($leading_in, $leading_in_error) =
    compile_property(' _ IN_Emoticons');
ok(defined $leading_in, 'In Block shortcut accepts leading loose separators')
    or diag($leading_in_error);
like(chr(0x1F600), $leading_in,
    'leading-loose In Block shortcut matches its block');
unlike('A', $leading_in,
    'leading-loose In Block shortcut excludes another block');

my ($leading_is, $leading_is_error) =
    compile_property('- Is_Emoticons');
ok(defined $leading_is, 'Is Block shortcut accepts leading loose separators')
    or diag($leading_is_error);
like(chr(0x1F600), $leading_is,
    'leading-loose Is Block shortcut matches its block');
unlike('A', $leading_is,
    'leading-loose Is Block shortcut excludes another block');

my ($script, $script_error) = compile_property('Is_Latin');
ok(defined $script, 'Script shortcut retains precedence') or diag($script_error);
like('A', $script, 'Script shortcut membership remains intact');

my ($binary, $binary_error) = compile_property('Is_Uppercase');
ok(defined $binary, 'binary shortcut retains precedence') or diag($binary_error);
like('A', $binary, 'binary shortcut membership remains intact');

my $callback_calls = 0;
sub Is_BlockShortcutCallback {
    $callback_calls++;
    return "0041";
}
my ($callback, $callback_error) =
    compile_property('Is_BlockShortcutCallback');
ok(defined $callback, 'exact user property callback retains precedence')
    or diag($callback_error);
like('A', $callback, 'exact user property callback matches its range');
is($callback_calls, 1, 'exact user property callback is called once');

my ($leading_callback, $leading_callback_error) =
    compile_property('_ Is_BlockShortcutCallback');
ok(!defined($leading_callback) && length($leading_callback_error),
    'leading separators do not broaden user property lookup');
is($callback_calls, 1, 'rejected leading callback spelling is not invoked');

my ($unknown, $unknown_error) = compile_property('_ In_DefinitelyNotABlock');
ok(!defined($unknown) && length($unknown_error),
    'unknown leading-loose Block shortcut is rejected');

done_testing;
