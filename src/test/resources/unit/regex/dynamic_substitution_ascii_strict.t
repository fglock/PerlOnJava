use strict;
use warnings;
use utf8;
use Test::More;

my $kelvin = "\N{KELVIN SIGN}";

sub dynamic_match {
    my ($subject, $pattern, $mode) = @_;
    return $mode eq 'iaa' ? ($subject =~ /$pattern/iaa ? 1 : 0)
         : $mode eq 'ia'  ? ($subject =~ /$pattern/ia  ? 1 : 0)
         :                  ($subject =~ /$pattern/i   ? 1 : 0);
}

sub dynamic_subst {
    my ($subject, $pattern, $mode) = @_;
    my $side_effects = 0;
    my $count = $mode eq 'iaa'
        ? ($subject =~ s/$pattern/$side_effects++; 'X'/eiaa)
        : $mode eq 'ia'
        ? ($subject =~ s/$pattern/$side_effects++; 'X'/eia)
        : ($subject =~ s/$pattern/$side_effects++; 'X'/ei);
    return [$count ? 1 : 0, $subject, $side_effects];
}

ok('k' =~ /\N{KELVIN SIGN}/ia,
   'literal match /ia permits Kelvin-to-ASCII fold');
ok('k' !~ /\N{KELVIN SIGN}/iaa,
   'literal match /iaa blocks Kelvin-to-ASCII fold');
{
    my $subject = 'k';
    is($subject =~ s/\N{KELVIN SIGN}/X/ia, 1,
       'literal substitution /ia permits Kelvin fold');
    is($subject, 'X', 'literal /ia replacement applied');
}
{
    my $subject = 'k';
    is($subject =~ s/\N{KELVIN SIGN}/X/iaa, '',
       'literal substitution /iaa blocks Kelvin fold');
    is($subject, 'k', 'literal /iaa leaves subject unchanged');
}

is(dynamic_match('k', $kelvin, 'ia'), 1,
   'dynamic match /ia permits Kelvin fold');
is(dynamic_match('k', $kelvin, 'iaa'), 0,
   'dynamic match /iaa blocks Kelvin fold');
is_deeply(dynamic_subst('k', $kelvin, 'ia'), [1, 'X', 1],
   'dynamic substitution /ia applies replacement once');
is_deeply(dynamic_subst('k', $kelvin, 'iaa'), [0, 'k', 0],
   'dynamic substitution /iaa blocks fold and replacement side effect');

is(dynamic_match($kelvin, 'k', 'ia'), 1,
   'reverse dynamic /ia permits ASCII-to-Kelvin fold');
is(dynamic_match($kelvin, 'k', 'iaa'), 0,
   'reverse dynamic /iaa blocks ASCII-to-Kelvin fold');
is_deeply(dynamic_subst($kelvin, 'k', 'ia'), [1, 'X', 1],
   'reverse substitution /ia applies replacement once');
is_deeply(dynamic_subst($kelvin, 'k', 'iaa'), [0, $kelvin, 0],
   'reverse substitution /iaa blocks replacement side effect');

{
    package A145::StringKelvin;
    use overload '""' => sub { "\N{KELVIN SIGN}" }, fallback => 1;
}
{
    package A145::QrLoose;
    use overload 'qr' => sub { qr/\N{KELVIN SIGN}/ia };
}
{
    package A145::QrStrict;
    use overload 'qr' => sub { qr/\N{KELVIN SIGN}/iaa };
}

my $string_kelvin = bless [], 'A145::StringKelvin';
is(dynamic_match('k', $string_kelvin, 'ia'), 1,
   'overloaded string match /ia permits Kelvin fold');
is(dynamic_match('k', $string_kelvin, 'iaa'), 0,
   'overloaded string match /iaa blocks Kelvin fold');
is_deeply(dynamic_subst('k', $string_kelvin, 'ia'), [1, 'X', 1],
   'overloaded string substitution /ia applies replacement');
is_deeply(dynamic_subst('k', $string_kelvin, 'iaa'), [0, 'k', 0],
   'overloaded string substitution /iaa remains strict');

my $qr_loose = bless [], 'A145::QrLoose';
my $qr_strict = bless [], 'A145::QrStrict';
is_deeply(dynamic_subst('k', $qr_loose, ''), [1, 'X', 1],
   'qr overload retains loose fold metadata in substitution');
is_deeply(dynamic_subst('k', $qr_strict, ''), [0, 'k', 0],
   'qr overload retains strict fold metadata in substitution');

{
    use re '/aa';
    my $subject = 'k';
    my $pattern = $kelvin;
    is($subject =~ s/$pattern/X/i, '',
       'lexical /aa makes dynamic substitution strict');
    {
        no re '/a';
        $subject = 'k';
        is($subject =~ s/$pattern/X/i, '',
           'no re /a does not cancel lexical /aa');
    }
    {
        no re '/aa';
        $subject = 'k';
        is($subject =~ s/$pattern/X/i, 1,
           'no re /aa cancels lexical strict ASCII mode');
    }
    $subject = 'k';
    is($subject =~ s/$pattern/X/ia, 1,
       'explicit /a replaces lexical /aa for substitution');
}
{
    use re '/a';
    my $subject = 'k';
    my $pattern = $kelvin;
    is($subject =~ s/$pattern/X/iaa, '',
       'explicit /aa strengthens lexical /a for substitution');
}

my $byte_k = 'k';
utf8::downgrade($byte_k, 1);
my $unicode_k = 'k';
utf8::upgrade($unicode_k);
is_deeply(dynamic_subst($byte_k, $kelvin, 'iaa'), [0, 'k', 0],
   'byte ASCII subject cannot cross to Unicode Kelvin under /iaa');
is_deeply(dynamic_subst($unicode_k, $kelvin, 'iaa'), [0, 'k', 0],
   'upgraded ASCII subject cannot cross to Unicode Kelvin under /iaa');

my $byte_pattern = 'k';
utf8::downgrade($byte_pattern, 1);
my $unicode_pattern = 'k';
utf8::upgrade($unicode_pattern);
is_deeply(dynamic_subst($kelvin, $byte_pattern, 'iaa'), [0, $kelvin, 0],
   'byte ASCII pattern cannot cross to Kelvin subject under /iaa');
is_deeply(dynamic_subst($kelvin, $unicode_pattern, 'iaa'), [0, $kelvin, 0],
   'upgraded ASCII pattern cannot cross to Kelvin subject under /iaa');

my $latin1_upper = "\x{00C4}";
my $latin1_lower = "\x{00E4}";
is_deeply(dynamic_subst($latin1_upper, $latin1_lower, 'iaa'), [1, 'X', 1],
   '/iaa preserves non-ASCII Unicode simple folding');

{
    my $pattern = $kelvin;
    is_deeply(dynamic_subst('k', $pattern, 'ia'), [1, 'X', 1],
       'cache sequence starts with loose Kelvin fold');
    is_deeply(dynamic_subst('k', $pattern, 'iaa'), [0, 'k', 0],
       'same source uses distinct strict cache identity');
    $pattern = 'k';
    is_deeply(dynamic_subst('k', $pattern, 'iaa'), [1, 'X', 1],
       'mutated source recompiles under strict flags');
    $pattern = $kelvin;
    is_deeply(dynamic_subst('k', $pattern, 'iaa'), [0, 'k', 0],
       'restored source does not reuse loose cached metadata');
}

done_testing;
