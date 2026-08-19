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

my @canonical_values = qw(
    ATerm Close CR Extend Format LF Lower Numeric OLetter Other SContinue
    Sep Sp STerm Upper
);
for my $value (@canonical_values) {
    my ($pattern, $error) = compile_property('p', "Sentence_Break=$value");
    ok(defined $pattern, "Sentence_Break=$value compiles") or diag($error);
}

check_property('p', 'SB=UP', 'A', 'a', 'short key and Upper value alias');
check_property('p', 'SB=LO', 'a', 'A', 'Lower value alias');
check_property('p', 'SB=NU', '5', 'A', 'Numeric value alias');
check_property('p', 'SB=AT', '.', 'A', 'ATerm value alias');
check_property('p', 'SB=ST', '!', 'A', 'STerm value alias');
check_property('p', 'SB=CL', ')', 'A', 'Close value alias');
check_property('p', 'SB=CR', "\r", "\n", 'CR value');
check_property('p', 'SB=LF', "\n", "\r", 'LF value');
check_property('p', 'SB=SE', chr(0x2029), 'A', 'Sep value alias');
check_property('p', 'SB=SP', ' ', 'A', 'Sp value');
check_property('p', 'Sentence Break : Upper', 'A', 'a',
    'loose long key and value with colon');

check_property('P', 'SB=UP', 'a', 'A', 'outer P negates Sentence_Break');
check_property('p', '^SB=UP', 'a', 'A', 'inner caret negates Sentence_Break');

my ($upper_i, $upper_i_error) = compile_property('p', 'SB=UP', 'iu');
ok(defined $upper_i, 'Sentence_Break compiles under /i') or diag($upper_i_error);
like('A', $upper_i, 'Sentence_Break matches Upper under /i');
unlike('a', $upper_i, 'no-fold Sentence_Break excludes Lower under /i');
unlike('5', $upper_i, 'Sentence_Break excludes Numeric under /i');

my $byte_a = pack('C', 0x41);
my $unicode_a = chr(0x41);
utf8::upgrade($unicode_a);
my ($upper, $upper_error) = compile_property('p', 'SB=UP');
ok(defined $upper, 'byte/Unicode Sentence_Break control compiles')
    or diag($upper_error);
like($byte_a, $upper, 'Sentence_Break matches byte input');
like($unicode_a, $upper, 'Sentence_Break matches upgraded input');

my @wide = (chr(0x110000), chr(0x7FFFFFFF), chr(hex('7FFFFFFFFFFFFFFF')));
my ($not_upper, $not_upper_error) = compile_property('P', 'SB=UP');
ok(defined($upper) && defined($not_upper), 'signed-wide controls compile')
    or diag($upper_error . $not_upper_error);
{
    local $SIG{__WARN__} = sub { };
    for my $index (0 .. $#wide) {
        unlike($wide[$index], $upper,
            "Sentence_Break excludes signed-wide control $index");
        like($wide[$index], $not_upper,
            "outer P includes signed-wide control $index");
    }
}

our $callback_calls = 0;
sub IsSentenceBreakAliasCallback {
    $callback_calls++;
    return '0041';
}
my ($callback, $callback_error) =
    compile_property('p', 'IsSentenceBreakAliasCallback');
ok(defined $callback, 'user property callback compiles') or diag($callback_error);
like('A', $callback, 'user property callback matches its range');
is($callback_calls, 1, 'user property callback is called once');

for my $rejected (
    'SB=',
    'SB=True',
    'SB=ALetter',
    'SB=NoSuchValue',
    'NoSuchSentenceBreak=Upper',
    'ATerm',
    'Sentence_Break=: \A(?:Upper|Lower)\z',
    'Sentence_Break=Yes',
) {
    my ($pattern, $error) = compile_property('p', $rejected);
    ok(!defined($pattern) && length($error), "$rejected remains rejected");
}

done_testing;
