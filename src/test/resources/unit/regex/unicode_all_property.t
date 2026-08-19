use strict;
use warnings;
no warnings qw(non_unicode portable);
use Config;
use Test::More;

my $is_perlonjava = $Config{archname} =~ /^java-/
    || $^X eq 'jperl'
    || $^X =~ m{(?:^|[\\/])jperl(?:\.bat)?$};
plan skip_all => 'native wide All is exercised by the forced-Joni gate'
    if $is_perlonjava
        && (!defined($ENV{JPERL_REGEX_BACKEND})
            || lc($ENV{JPERL_REGEX_BACKEND}) ne 'joni');

my @boundaries = (
    ['U+0000', chr(0x0000)],
    ['U+10FFFF', chr(0x10FFFF)],
    ['U+110000', chr(0x110000)],
    ['U+7FFFFFFF', chr(0x7FFFFFFF)],
    ['U+80000000', chr(0x80000000)],
    ['U+7FFFFFFFFFFFFFFF', chr(hex('7FFFFFFFFFFFFFFF'))],
);

sub compile_pattern {
    my ($source, $flags) = @_;
    $flags //= 'u';
    my $pattern = eval 'qr/' . $source . '/' . $flags;
    return ($pattern, $@);
}

sub check_all_form {
    my ($label, $positive_source, $negative_source) = @_;
    my ($positive, $positive_error) = compile_pattern($positive_source);
    my ($negative, $negative_error) = compile_pattern($negative_source);
    ok(defined $positive, "$label positive compiles") or diag($positive_error);
    ok(defined $negative, "$label complement compiles") or diag($negative_error);
    for my $boundary (@boundaries) {
        my ($name, $value) = @$boundary;
        like($value, $positive, "$label All includes $name");
        unlike($value, $negative, "$label complement excludes $name");
    }
}

check_all_form('direct', '\A\p{All}\z', '\A\P{All}\z');
check_all_form('ordinary class', '\A[\p{All}]\z', '\A[\P{All}]\z');
check_all_form('extended class', '\A(?[\p{All}])\z', '\A(?[\P{All}])\z');

for my $property ('All', 'all', '_ Is_All', '_ is_all') {
    my ($pattern, $error) = compile_pattern('\A\p{' . $property . '}\z');
    ok(defined $pattern, "$property spelling compiles") or diag($error);
    like(chr(0), $pattern, "$property spelling includes U+0000");
    like(chr(hex('7FFFFFFFFFFFFFFF')), $pattern,
        "$property spelling includes U+7FFFFFFFFFFFFFFF");
}

my ($folded, $folded_error) = compile_pattern('\A\p{_ Is_All}\z', 'iu');
my ($folded_complement, $folded_complement_error) =
    compile_pattern('\A\P{_ Is_All}\z', 'iu');
ok(defined $folded, 'All compiles under /i') or diag($folded_error);
ok(defined $folded_complement, 'All complement compiles under /i')
    or diag($folded_complement_error);
like(chr(hex('7FFFFFFFFFFFFFFF')), $folded,
    'All retains signed-IV-wide membership under /i');
unlike(chr(hex('7FFFFFFFFFFFFFFF')), $folded_complement,
    'All complement retains signed-IV-wide exclusion under /i');

my $byte = pack('C', 0xFF);
my $upgraded = chr(0xFF);
utf8::upgrade($upgraded);
my ($all, $all_error) = compile_pattern('\A\p{All}\z');
my ($not_all, $not_all_error) = compile_pattern('\A\P{All}\z');
ok(defined($all) && defined($not_all), 'byte/Unicode controls compile')
    or diag($all_error . $not_all_error);
like($byte, $all, 'All includes a byte character');
like($upgraded, $all, 'All includes its upgraded character');
unlike($byte, $not_all, 'All complement excludes a byte character');
unlike($upgraded, $not_all, 'All complement excludes its upgraded character');

my ($wide_only, $wide_only_error) =
    compile_pattern('\A(?[\p{All} - \p{Unicode}])\z');
ok(defined $wide_only, 'extended All-minus-Unicode compiles')
    or diag($wide_only_error);
unlike(chr(0x10FFFF), $wide_only,
    'All-minus-Unicode excludes the Unicode maximum');
like(chr(0x110000), $wide_only,
    'All-minus-Unicode includes the first non-Unicode scalar');
like(chr(hex('7FFFFFFFFFFFFFFF')), $wide_only,
    'All-minus-Unicode includes the signed-IV scalar maximum');

my ($empty_intersection, $empty_intersection_error) =
    compile_pattern('\A(?[\p{All} & \P{All}])\z');
ok(defined $empty_intersection, 'extended All/complement intersection compiles')
    or diag($empty_intersection_error);
unlike('A', $empty_intersection,
    'All intersect complement excludes ordinary input');
unlike(chr(hex('7FFFFFFFFFFFFFFF')), $empty_intersection,
    'All intersect complement excludes signed-IV-wide input');

my ($class_union, $class_union_error) =
    compile_pattern('\A[\P{All}A]\z');
ok(defined $class_union, 'ordinary complement/literal union compiles')
    or diag($class_union_error);
like('A', $class_union, 'ordinary class union retains its literal member');
unlike(chr(hex('7FFFFFFFFFFFFFFF')), $class_union,
    'ordinary class union excludes unrelated signed-IV-wide input');

our $all_callback_calls = 0;
my $definition_ok = eval q{
    sub IsAll {
        $all_callback_calls++;
        return "0041";
    }
    1;
};
ok($definition_ok, 'exact IsAll user callback installs') or diag($@);
my ($callback, $callback_error) = compile_pattern('\A\p{IsAll}\z');
ok(defined $callback, 'exact IsAll user callback retains precedence')
    or diag($callback_error);
like('A', $callback, 'IsAll user callback matches its returned range');
unlike(chr(hex('7FFFFFFFFFFFFFFF')), $callback,
    'IsAll user callback shadows the built-in Is spelling');
is($all_callback_calls, 1, 'IsAll user callback is called once');

my ($unknown, $unknown_error) =
    compile_pattern('\A\p{_ Is_NoSuchAllProperty}\z');
ok(!defined($unknown) && length($unknown_error),
    'unknown leading-Is All-style property remains rejected');

done_testing;
