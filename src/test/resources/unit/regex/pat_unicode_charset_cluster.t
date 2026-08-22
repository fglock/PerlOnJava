use strict;
use warnings;
use feature 'unicode_strings';
use Test::More;

my $latin_capital_a_grave = "\xC0";
ok($latin_capital_a_grave =~ /\w/,
    'unicode_strings gives an unqualified word class Unicode semantics');
ok($latin_capital_a_grave !~ /(?d:\w)/,
    'an inline /d word class keeps byte semantics for a byte-backed subject');

{
    my $count = 0;
    while ('abc' =~ /(\G[ac])?/g) { last if $count++ > 10 }
    ok($count < 10, 'RT 3516 A global matching advances');
}
{
    my $count = 0;
    while ('abc' =~ /(\G|.)[ac]/g) { last if $count++ > 10 }
    ok($count < 10, 'RT 3516 B global matching advances');
}
{
    my $count = 0;
    while ('abc' =~ /(\G?[ac])?/g) { last if $count++ > 10 }
    ok($count < 10, 'RT 3516 C global matching advances');
}

{
    no warnings 'utf8';
    my $wide = chr(0x110000);
    ok($wide !~ /\p{ASCII_Hex_Digit=True}/,
        'wide non-Unicode scalar is outside AHEX=True');
    ok($wide =~ /\p{ASCII_Hex_Digit=False}/,
        'wide non-Unicode scalar is inside AHEX=False');
    ok($wide =~ /\P{ASCII_Hex_Digit=True}/,
        'wide non-Unicode scalar is inside negated AHEX=True');
    ok($wide !~ /\P{ASCII_Hex_Digit=False}/,
        'wide non-Unicode scalar is outside negated AHEX=False');
}

SKIP: {
    skip 'capture-name XID_Continue policy was added after Perl 5.34', 2
        if $^V lt v5.45.0;
    no warnings 'utf8';
    my $circled_b = chr(0x24B7);
    my $program = qq{use utf8; no warnings 'utf8'; qr/(?<a${circled_b}b>abc)/};
    utf8::encode($program);
    my $compiled = eval $program;
    ok(!$compiled, 'U+24B7 is rejected in a capture name');
    like($@,
        qr/\\x\{24B7\} is a \\w char that isn't valid in a name; marked by <-- HERE after /,
        'U+24B7 rejection preserves Perl word-versus-identifier provenance');
}

{
    require POSIX;
    my $saved_locale = POSIX::setlocale(&POSIX::LC_CTYPE);
    POSIX::setlocale(&POSIX::LC_CTYPE, 'C');
    my $sharp_s = "\xDF";
    my $pattern = $sharp_s x 260;
    my $subject = 'ss' x 260;
    my $byte_eval = "'$subject' =~ qr/(?id)$pattern/;";
    ok(!utf8::is_utf8($byte_eval),
        'byte sharp-s interpolation produces byte eval source');
    ok(!eval($byte_eval),
        'byte /d sharp-s pattern keeps byte fold semantics');
    ok(!eval("'a$subject' =~ qr/(?id)a$pattern/;"),
        'byte /d sharp-s pattern with leading exact keeps byte fold semantics');
    utf8::upgrade($pattern);
    my $unicode_eval = "'$subject' =~ qr/(?id)$pattern/;";
    ok(utf8::is_utf8($unicode_eval),
        'upgraded sharp-s interpolation produces Unicode eval source');
    ok(eval($unicode_eval),
        'UTF-8 /d sharp-s pattern uses Unicode full-fold semantics');
    ok(eval("'a$subject' =~ qr/(?id)a$pattern/;"),
        'UTF-8 /d sharp-s pattern retains a leading exact node');
    POSIX::setlocale(&POSIX::LC_CTYPE, $saved_locale)
        if defined $saved_locale;
}

done_testing;
