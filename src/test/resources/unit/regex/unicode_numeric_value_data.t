use strict;
use warnings;
use utf8;
use Test::More;
use Unicode::UCD ();

sub compile_property {
    my ($expression) = @_;
    local $SIG{__WARN__} = sub { };
    return eval "qr!\\p{$expression}!";
}

sub matches_property {
    my ($expression, $code_point) = @_;
    my $regex = compile_property($expression);
    return defined($regex) && chr($code_point) =~ $regex;
}

    ok(matches_property('nv=1', 0x0031), 'short property name and integer value');
    ok(matches_property('Numeric_Value:1', 0x0031), 'long property name and colon');
    ok(matches_property('nU-mErIc value=+ 000_001', 0x0031),
        'property and integer spellings use Perl loose matching');
    ok(matches_property('Is_NV=1', 0x0031), 'short exact-case Is property form');
    ok(matches_property('Is Numeric Value=1', 0x0031),
        'long exact-case Is property form is loose after Is');
    ok(!defined(compile_property('is_nv=1')), 'Is property prefix remains case-sensitive');

    for my $value ('1/2', '+1/2', '01/02', '10/20', '3/6', '1/_2', '1_0/2_0',
            '1/+2', '0.5', '00.500', '5e-1') {
        ok(matches_property("nv=$value", 0x00BD), "half spelling $value");
    }
    for my $value ('-1/2', '-01/02', '-0.5', '-.5', '-5e-1') {
        ok(matches_property("nv=$value", 0x0F33), "negative half spelling $value");
    }

    ok(matches_property('nv=10000000000000000', 0x4EAC),
        'maximum Unicode numeric value fits an exact integer');
    ok(matches_property('nv=NaN', 0x0041), 'NaN includes assigned nonnumeric code points');
    ok(matches_property('nv=N_A_N', 0x0378), 'NaN alias is loose and includes unassigned code points');
    ok(matches_property('nv=nan', 0x10FFFF), 'NaN includes the Unicode maximum');
    ok(!matches_property('nv=NaN', 0x0031), 'NaN excludes numeric code points');

    for my $value ('.5', '1 / 2', '1_/2', '1/_/2', '1.0/2.0', '1/-2', '0/0',
            '1/0', 'None', 'Not_A_Number') {
        ok(!defined(compile_property("nv=$value")), "invalid numeric spelling $value");
    }

    ok(matches_property('nv=/\A1\/2\z/', 0x00BD),
        'wildcard value matches the canonical rational spelling');
    ok(matches_property('nv=:\A300\z:', 0x1011B),
        'alternate wildcard delimiter matches an integer value');
    ok(!defined(compile_property('Is_Nv=/\A1\z/')),
        'Is-prefixed property names reject wildcard values');
    ok(!defined(compile_property('nv=*')), 'bare wildcard marker is rejected');

    SKIP: {
        skip 'requires Unicode 17 Numeric_Value data', 2
            if Unicode::UCD::UnicodeVersion() lt '17.0.0';
        ok(matches_property('nv=3/2', 0x16FF5), 'Unicode 17 fractional-value boundary');
        ok(!matches_property('nv=3/2', 0x16FF6), 'Unicode 17 adjacent nonmatch boundary');
    }
done_testing;
