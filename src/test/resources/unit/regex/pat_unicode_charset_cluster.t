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
    my @spans;
    while ('abc' =~ /(\G[ac])?/g) {
        push @spans, "$-[0]-$+[0]";
        last if @spans > 10;
    }
    is(join(', ', @spans), '0-1, 1-1, 2-2, 2-3, 3-3',
        'RT 3516 A preserves every zero-width and consuming match');
}
{
    my @spans;
    while ('abc' =~ /(\G|.)[ac]/g) {
        push @spans, "$-[0]-$+[0]";
        last if @spans > 10;
    }
    is(join(', ', @spans), '0-1, 1-3',
        'RT 3516 B preserves its consuming progression');
}
{
    my @spans;
    while ('abc' =~ /(\G?[ac])?/g) {
        push @spans, "$-[0]-$+[0]";
        last if @spans > 10;
    }
    is(join(', ', @spans), '0-1, 1-1, 2-3, 3-3',
        'RT 3516 C preserves every zero-width and consuming match');
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

{
    my $wide;
    { no warnings 'utf8'; $wide = chr(0x110000) }
    my $shape = 'Matched non-Unicode code point 0x110000 against Unicode property; may not be portable';
    my @cases = (
        [ 'p=True',  0, 0, sub { $wide =~ /\p{ASCII_Hex_Digit=True}/ } ],
        [ 'p=False', 1, 2, sub { $wide =~ /\p{ASCII_Hex_Digit=False}/ } ],
        [ 'P=True',  1, 0, sub { $wide =~ /\P{ASCII_Hex_Digit=True}/ } ],
        [ 'P=False', 0, 1, sub { $wide =~ /\P{ASCII_Hex_Digit=False}/ } ],
    );
    for my $case (@cases) {
        my @warnings;
        my $matched;
        {
            local $SIG{__WARN__} = sub { push @warnings, @_ };
            $matched = $case->[3]->();
        }
        is(0 + !!$matched, $case->[1], "$case->[0] wide membership");
        is(scalar @warnings, $case->[2], "$case->[0] warning count");
        for my $warning (@warnings) {
            $warning =~ s/ at \Q$0\E line \d+\.\n?\z//;
            is($warning, $shape, "$case->[0] warning shape");
        }
    }

    for my $category (qw(utf8 non_unicode)) {
        my $result = eval qq{
            use warnings FATAL => '$category';
            my \$subject;
            { no warnings 'utf8'; \$subject = chr(0x110000) }
            \$subject =~ /\\P{ASCII_Hex_Digit=False}/;
            1;
        };
        ok(!$result, "$category fatalizes the non-Unicode property warning");
        my $error = $@;
        $error =~ s/ at \(eval \d+\) line \d+\.\n?\z//;
        is($error, $shape, "$category fatal warning shape");
    }

    my @blocked;
    {
        local $SIG{__WARN__} = sub { push @blocked, @_ };
        ('z' . $wide) =~ /q\p{ASCII_Hex_Digit=False}/;
    }
    is(scalar @blocked, 0,
        'a property that execution never reaches emits no warning');

    my @reached;
    {
        local $SIG{__WARN__} = sub { push @reached, @_ };
        ('q' . $wide) =~ /q\p{ASCII_Hex_Digit=False}/;
    }
    is(scalar @reached, 1,
        'a reached property warns at match execution time');
}

{
    no warnings 'utf8';
    my $circled_b = chr(0x24B7);
    my $program = qq{use utf8; no warnings 'utf8'; qr/(?<a${circled_b}b>abc)/};
    utf8::encode($program);
    my $compiled = eval $program;
    if ($compiled) {
        pass('installed Perl capability predates U+24B7 capture-name rejection');
        pass('latest imported Perl supplies the U+24B7 policy authority');
    }
    else {
        ok(!$compiled, 'U+24B7 is rejected in a capture name');
        like($@,
            qr/\\x\{24B7\} is a \\w char that isn't valid in a name; marked by <-- HERE after /,
            'U+24B7 rejection preserves Perl word-versus-identifier provenance');
    }
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
