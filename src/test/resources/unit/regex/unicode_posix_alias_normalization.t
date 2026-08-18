use strict;
use warnings;
use Test::More;

sub compile_property {
    my ($property) = @_;
    my $pattern = eval 'qr/\A\p{' . $property . '}\z/u';
    return ($pattern, $@);
}

my ($space, $space_error) = compile_property('XPosixSpace');
ok(defined $space, 'canonical XPosix property compiles') or diag($space_error);
like(chr(0x00A0), $space, 'XPosixSpace includes non-ASCII Unicode space');
unlike('A', $space, 'XPosixSpace excludes a letter');

my ($ascii_alpha, $ascii_alpha_error) = compile_property('PosixAlpha');
ok(defined $ascii_alpha, 'canonical ASCII Posix property compiles')
    or diag($ascii_alpha_error);
like('A', $ascii_alpha, 'PosixAlpha includes an ASCII letter');
unlike(chr(0x03B1), $ascii_alpha,
    'PosixAlpha remains ASCII-only');

my ($loose, $loose_error) = compile_property('x-p_o s_i x alpha');
ok(defined $loose, 'XPosix property accepts loose internal separators')
    or diag($loose_error);
like(chr(0x03B1), $loose, 'loose XPosixAlpha keeps Unicode membership');

my ($leading, $leading_error) = compile_property('  _XPosixWord');
ok(defined $leading, 'XPosix property accepts leading loose separators')
    or diag($leading_error);
like('_', $leading, 'leading-separator XPosixWord matches underscore');

my ($is_prefixed, $is_prefixed_error) = compile_property('isxposixxdigit');
ok(defined $is_prefixed, 'XPosix property accepts a loose Is prefix')
    or diag($is_prefixed_error);
like('F', $is_prefixed, 'Is-prefixed XPosixXDigit matches a hex digit');
unlike('G', $is_prefixed, 'Is-prefixed XPosixXDigit excludes a non-hex digit');

my ($unknown, $unknown_error) = compile_property('xposixdefinitelynot');
ok(!defined($unknown) && length($unknown_error),
    'unknown loose XPosix property is rejected');

my ($unknown_is, $unknown_is_error) =
    compile_property('Is_XPosixDefinitelyNot');
eval { 'A' =~ $unknown_is } if defined($unknown_is) && !$unknown_is_error;
$unknown_is_error ||= $@;
ok(length($unknown_is_error), 'unknown Is-prefixed XPosix property is rejected');

my $callback_calls = 0;
my $callback_defined = eval q{
    sub Is_XPosixDefinitelyNot {
        $callback_calls++;
        return "0041";
    }
    1;
};
ok($callback_defined, 'POSIX-shaped user property callback can be defined');

my ($callback, $callback_error) =
    compile_property('Is_XPosixDefinitelyNot');
ok(defined $callback, 'defined POSIX-shaped user property callback wins')
    or diag($callback_error);
like('A', $callback, 'defined POSIX-shaped user property matches its range');
unlike('B', $callback, 'defined POSIX-shaped user property excludes other ranges');
is($callback_calls, 1, 'defined POSIX-shaped user property is called once');

done_testing;
