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

for my $value (qw(Rotated Transformed_Rotated Transformed_Upright Upright)) {
    my ($pattern, $error) = compile_property('p', "Vertical_Orientation=$value");
    ok(defined $pattern, "Vertical_Orientation=$value compiles") or diag($error);
}

check_property('p', 'VO=R', 'A', chr(0x4E00), 'short Rotated aliases');
check_property('p', 'Vertical_Orientation=Rotated', 'z', chr(0x4E00),
    'long Rotated aliases');
check_property('p', 'VO=TR', chr(0x2329), 'A',
    'Transformed Rotated value alias');
check_property('p', 'Vertical_Orientation=Transformed_Rotated', chr(0x232A), 'A',
    'long Transformed Rotated aliases');
check_property('p', 'VO=TU', chr(0x3001), 'A',
    'Transformed Upright value alias');
check_property('p', 'Vertical_Orientation=Transformed_Upright', chr(0x3002), 'A',
    'long Transformed Upright aliases');
check_property('p', 'VO=U', chr(0x4E00), 'A', 'Upright value alias');
check_property('p', 'Vertical Orientation : Upright', chr(0x4E8C), 'A',
    'loose long key and value with colon');

check_property('P', 'VO=R', chr(0x4E00), 'A',
    'outer P negates Vertical_Orientation');
check_property('p', '^VO=R', chr(0x4E00), 'A',
    'inner caret negates Vertical_Orientation');

my ($rotated_i, $rotated_i_error) = compile_property('p', 'VO=R', 'iu');
ok(defined $rotated_i, 'Vertical_Orientation compiles under /i')
    or diag($rotated_i_error);
like('A', $rotated_i, 'Vertical_Orientation matches Rotated under /i');
like('a', $rotated_i, 'Vertical_Orientation matches lowercase Rotated under /i');
unlike(chr(0x4E00), $rotated_i,
    'Vertical_Orientation excludes Upright under /i');

my $byte_a = pack('C', 0x41);
my $unicode_a = chr(0x41);
utf8::upgrade($unicode_a);
my ($rotated, $rotated_error) = compile_property('p', 'VO=R');
ok(defined $rotated, 'byte/Unicode Vertical_Orientation control compiles')
    or diag($rotated_error);
like($byte_a, $rotated, 'Vertical_Orientation matches byte input');
like($unicode_a, $rotated, 'Vertical_Orientation matches upgraded input');

my @wide = (chr(0x110000), chr(0x7FFFFFFF), chr(hex('7FFFFFFFFFFFFFFF')));
my ($not_rotated, $not_rotated_error) = compile_property('P', 'VO=R');
ok(defined($rotated) && defined($not_rotated), 'signed-wide controls compile')
    or diag($rotated_error . $not_rotated_error);
{
    local $SIG{__WARN__} = sub { };
    for my $index (0 .. $#wide) {
        like($wide[$index], $rotated,
            "default Rotated includes signed-wide control $index");
        unlike($wide[$index], $not_rotated,
            "outer P excludes signed-wide Rotated control $index");
    }
}

our $callback_calls = 0;
sub IsVerticalOrientationAliasCallback {
    $callback_calls++;
    return '0041';
}
my ($callback, $callback_error) =
    compile_property('p', 'IsVerticalOrientationAliasCallback');
ok(defined $callback, 'user property callback compiles') or diag($callback_error);
like('A', $callback, 'user property callback matches its range');
is($callback_calls, 1, 'user property callback is called once');

for my $rejected (
    'VO=',
    'VO=True',
    'VO=ALetter',
    'VO=NoSuchValue',
    'NoSuchVerticalOrientation=Upright',
    'Transformed_Rotated',
    'Vertical_Orientation=: \A(?:Upright|Rotated)\z',
    'Vertical_Orientation=Yes',
) {
    my ($pattern, $error) = compile_property('p', $rejected);
    ok(!defined($pattern) && length($error), "$rejected remains rejected");
}

done_testing;
