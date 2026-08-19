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

my @assigned_aliases = qw(
    ahex alnum alpha any assigned bidim bidimirrored changeswhencasemapped
    changeswhenlowercased changeswhennfkccasefolded closepunctuation cntrl
    combiningmark compex connectorpunctuation cwu cwkcf dash dashpunctuation
    decimalnumber deprecated dia emojimodifierbase emojipresentation epres
    extender fullcompositionexclusion grbase grext hexdigit ideo idst loe
    logicalorderexception lowercase othernumber otherpunctuation regionalindicator
    sentenceterminal softdotted space spaceseparator uideo unassigned
    unifiedideograph xids xidstart
);
for my $alias (@assigned_aliases) {
    my ($pattern, $error) = compile_property('p', $alias);
    ok(defined $pattern, "bare alias $alias compiles") or diag($error);
}

check_property('p', 'bidimirrored', '(', 'A', 'loose Bidi_Mirrored alias');
check_property('p', 'Bidi_M', '(', 'A', 'short Bidi_Mirrored alias');
check_property('p', '_ Alnum', 'A', '+', 'leading-loose Alnum alias');
check_property('p', ' alpha', 'z', '+', 'leading-loose Alpha alias');
check_property('p', ' -ASSIGNED', 'A', chr(0x0378),
    'leading-loose Assigned pseudo-property');
check_property('p', 'Close_Punctuation', ')', 'A',
    'General_Category long value alias');
check_property('p', 'Combining_Mark', chr(0x0301), 'A',
    'General_Category aggregate alias');
check_property('p', 'Unassigned', chr(0x0378), 'A',
    'General_Category Unassigned alias');
check_property('p', 'Emoji_Presentation', chr(0x1F600), 'A',
    'binary Emoji_Presentation alias');
check_property('p', 'Changes_When_Lowercased', 'A', 'a',
    'binary Changes_When_Lowercased alias');

my ($any, $any_error) = compile_property('p', " \tany");
ok(defined $any, 'leading-loose Any compiles') or diag($any_error);
like('A', $any, 'Any matches ASCII');
like(chr(0x10FFFF), $any, 'Any matches the top Unicode scalar');

check_property('P', 'bidimirrored', 'A', '(',
    'outer P negates bare binary alias');
check_property('p', '^bidimirrored', 'A', '(',
    'inner caret negates bare binary alias');

my ($changes_i, $changes_i_error) =
    compile_property('p', 'Changes_When_Lowercased', 'iu');
ok(defined $changes_i, 'bare binary alias compiles under /i')
    or diag($changes_i_error);
like('A', $changes_i, 'binary property matches its member under /i');
unlike('a', $changes_i, 'no-fold binary property excludes its fold under /i');
unlike('1', $changes_i, 'binary property excludes unrelated input under /i');

my $byte_a = pack('C', 0x41);
my $unicode_a = chr(0x41);
utf8::upgrade($unicode_a);
my ($alpha, $alpha_error) = compile_property('p', 'alpha');
ok(defined $alpha, 'byte/Unicode bare alias control compiles')
    or diag($alpha_error);
like($byte_a, $alpha, 'bare alias matches byte input');
like($unicode_a, $alpha, 'bare alias matches upgraded input');

my @wide = (
    chr(0x110000),
    "\x{87FFF7}",
    chr(0x7FFFFFFF),
    chr(hex('7FFFFFFFFFFFFFFF')),
);
my ($not_any, $not_any_error) = compile_property('P', 'any');
my ($mirrored, $mirrored_error) = compile_property('p', 'bidimirrored');
my ($not_mirrored, $not_mirrored_error) = compile_property('P', 'bidimirrored');
ok(defined($any) && defined($not_any) && defined($mirrored)
        && defined($not_mirrored), 'signed-wide controls compile')
    or diag($any_error . $not_any_error . $mirrored_error . $not_mirrored_error);
{
    local $SIG{__WARN__} = sub { };
    for my $index (0 .. $#wide) {
        unlike($wide[$index], $any, "Any excludes signed-wide control $index");
        like($wide[$index], $not_any,
            "outer P Any includes signed-wide control $index");
        unlike($wide[$index], $mirrored,
            "binary alias excludes signed-wide control $index");
        like($wide[$index], $not_mirrored,
            "outer P binary alias includes signed-wide control $index");
    }
}

for my $case (
    ['\\p{ ' . "\t" . 'any}', 0, 'p Any'],
    ['\\p{^ ' . "\t" . 'any}', 1, 'inner-negated p Any'],
    ['\\P{ ' . "\t" . 'any}', 1, 'P Any'],
    ['\\P{^ ' . "\t" . 'any}', 0, 'inner-negated P Any'],
    ['\\p{Unicode}', 0, 'p Unicode'],
    ['\\P{Unicode}', 1, 'P Unicode'],
    ['\\p{IsAny}', 0, 'p IsAny'],
    ['\\P{IsAny}', 1, 'P IsAny'],
    ['\\p{IsUnicode}', 0, 'p IsUnicode'],
    ['\\P{IsUnicode}', 1, 'P IsUnicode'],
) {
    my ($regex, $expected, $label) = @$case;
    my $warning;
    my $result;
    {
        local $SIG{__WARN__} = sub { $warning = $_[0] };
        $result = eval 'use warnings; "\\x{87FFF7}" =~ qr('
                . $regex . ') ? 1 : 0';
    }
    is($result, $expected, "$label handles a literal signed-wide scalar");
    ok(!defined $warning, "$label emits no signed-wide warning");
}

our $callback_calls = 0;
sub IsBareAliasCallback {
    $callback_calls++;
    return '0041';
}
my ($callback, $callback_error) = compile_property('p', 'IsBareAliasCallback');
ok(defined $callback, 'user property callback compiles') or diag($callback_error);
like('A', $callback, 'user property callback matches its range');
is($callback_calls, 1, 'user property callback is called once');

my ($callback_assignment, $callback_assignment_error) =
    compile_property('p', 'IsBareAliasCallback=Yes');
ok(!defined($callback_assignment) && length($callback_assignment_error),
    'binary assignment does not broaden callback lookup');
is($callback_calls, 1, 'rejected callback assignment is not invoked');

for my $rejected (
    'NoSuchBareProperty',
    '_ -',
    'Bidi_Mirrored=Maybe',
    'Bidi_Mirrored=: \A(?:Y|N)\z',
    'Any=Maybe',
) {
    my ($pattern, $error) = compile_property('p', $rejected);
    ok(!defined($pattern) && length($error), "$rejected remains rejected");
}

done_testing;
