use strict;
use warnings;
use Test::More tests => 16;

{
    my $seen = 0;
    my $embedded = qr/(??{"Q"})/;
    my $runtime = '(??{"R"})';
    use re 'eval';
    ok("LQR" =~ /(?{$seen++})L$embedded$runtime/,
        'literal, qr, and runtime callbacks compose');
    is($seen, 1, 'literal callback executes once');
}

{
    use re 'eval';
    my $runtime = q{(??{'\d'})};
    ok("1" =~ /^(?{1})$runtime(?(DEFINE)(?<DIGIT>\d))$/,
        'runtime source keeps a standalone DEFINE container on Joni');
}

{
    use re 'eval';
    my $runtime = '(??{"A"})';
    ok("ABC" =~ /^$runtime(?(?{1})BC|XY)$/,
        'runtime source composes with a true compile-time callback condition');
    ok("AXY" =~ /^$runtime(?(?{0})BC|XY)$/,
        'runtime source composes with a false compile-time callback condition');
}

{
    my $matched = '';
    for (qw(a a a)) {
        $matched .= $_ if m?$_?;
    }
    is($matched, 'a', 'interpolated match-once pattern retains callsite state');
}

{
    use re 'eval';
    my $matched = '';
    for (qw(a a a)) {
        my $runtime = qq[(??{"$_"})];
        $matched .= $_ if m?$runtime?;
    }
    is($matched, 'a', 'runtime-source match-once pattern retains callsite state');
}

{
    my $returned = 'B(??{1})C';
    use re 'eval';
    ok("AB1CD" =~ /^A(??{$returned})D$/,
        'use re eval propagates into a returned dynamic pattern');
}

{
    my $returned = 'B(??{1})C';
    my $error = '';
    eval { "AB1CD" =~ /^A(??{$returned})D$/ };
    $error = $@;
    like($error, qr/Eval-group not allowed at runtime/,
        'returned dynamic source still requires use re eval');
}

{
    my $runtime = '(??{"R"})';
    my $error = '';
    eval { "R" =~ /(?{})$runtime/ };
    $error = $@;
    like($error, qr/Eval-group not allowed at runtime/,
        'mixed runtime source still requires use re eval');
}

{
    my $runtime = '(??{"R"})';
    my $error = '';
    eval { "R" =~ $runtime };
    $error = $@;
    like($error, qr/Eval-group not allowed at runtime/,
        'plain runtime source still requires use re eval');
}

{
    my $comment = '(?# (??{"not executable"})R';
    ok("R" =~ /$comment/, 'eval-group text inside a regex comment is inert');

    my $extended = "# (??{\"not executable\"})\nR";
    ok("R" =~ /$extended/x, 'eval-group text inside an extended comment is inert');
}

{
    my $value = 'B';
    my @parts = ('A', qr/(??{$value})/, 'C');
    {
        my $value = 'X';
        ok("A B C" =~ /@parts/,
            'array interpolation preserves trusted qr callbacks');
    }
}

{
    my $runtime = "(??{qw(\x{100})})";
    use re 'eval';
    ok("\x{100}" =~ /^$runtime$/,
        'runtime callback source retains Unicode characters');
}

{
    my $depth = 2;
    my $embedded = qr/(??{"Q$depth"})/;
    my $runtime = '(??{"R$depth"})';
    use re 'eval';
    ok("AQ2R2" =~ /^A$embedded$runtime$/,
        'runtime callbacks retain lexical cells');
}
