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

check_property('KEH_NoMirror', chr(0x13081), chr(0x13082),
    'KEH_NoMirror bare alias');
check_property('kehnomirror', chr(0x130BD), chr(0x130BC),
    'KEH_NoMirror loose bare alias');
check_property('_ Is_KEH_NoMirror', chr(0x13084), chr(0x13085),
    'KEH_NoMirror leading-Is alias');

check_property('KEH_NoRotate', chr(0x13021), chr(0x13022),
    'KEH_NoRotate bare alias');
check_property('keh_NoRotate', chr(0x1439A), chr(0x1439C),
    'KEH_NoRotate loose bare alias');
check_property('_ Is_KEH_NoRotate', chr(0x143E8), chr(0x143E7),
    'KEH_NoRotate leading-Is alias');

for my $case (
    ['KEH_NoMirror', chr(0x130BB), chr(0x130BC)],
    ['_ Is_KEH_NoMirror', chr(0x130BD), chr(0x130BE)],
    ['KEH_NoRotate', chr(0x1329C), chr(0x1329D)],
    ['_ Is_KEH_NoRotate', chr(0x1342B), chr(0x1342C)],
) {
    my ($property, $member, $nonmember) = @$case;
    my ($pattern, $error) = compile_property($property, 'iu');
    ok(defined $pattern, "$property compiles under /i") or diag($error);
    like($member, $pattern, "$property retains membership under /i");
    unlike($nonmember, $pattern, "$property retains exclusion under /i");
}

my $byte = pack('C', 0xFF);
my $upgraded = chr(0xFF);
utf8::upgrade($upgraded);
my ($mirror, $mirror_error) = compile_property('KEH_NoMirror');
ok(defined $mirror, 'KEH_NoMirror byte/Unicode control compiles')
    or diag($mirror_error);
unlike($byte, $mirror, 'KEH_NoMirror excludes a byte character');
unlike($upgraded, $mirror, 'KEH_NoMirror excludes its upgraded character');
my ($rotate, $rotate_error) = compile_property('KEH_NoRotate');
ok(defined $rotate, 'KEH_NoRotate byte/Unicode control compiles')
    or diag($rotate_error);
unlike($byte, $rotate, 'KEH_NoRotate excludes a byte character');
unlike($upgraded, $rotate, 'KEH_NoRotate excludes its upgraded character');

our $unikemet_callback_calls = 0;
my $definition_ok = eval q{
    sub IsKEH_NoMirror {
        $unikemet_callback_calls++;
        return "0041";
    }
    1;
};
ok($definition_ok, 'exact user property callback installs') or diag($@);
my ($callback, $callback_error) = compile_property('IsKEH_NoMirror');
ok(defined $callback, 'exact user property callback retains precedence')
    or diag($callback_error);
like('A', $callback, 'user property callback matches its returned range');
unlike(chr(0x13081), $callback,
    'user property callback shadows the built-in Is spelling');
is($unikemet_callback_calls, 1, 'user property callback is called once');

my ($unknown, $unknown_error) = compile_property('_ Is_KEH_NoSuchProperty');
ok(!defined($unknown) && length($unknown_error),
    'unknown leading-Is Unikemet-style property remains rejected');

done_testing;
