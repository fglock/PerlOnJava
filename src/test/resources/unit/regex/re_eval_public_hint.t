use strict;
use warnings;
use Test::More tests => 10;

my $public_re_eval_hint = 0x00200000;

{
    BEGIN { $^H |= 0x00200000 }
    my $seen = 0;
    my $runtime = '(?{$seen++})';
    ok('a' =~ /^a$runtime$/, 'public re-eval hint admits runtime code');
    is($seen, 1, 'runtime code admitted by the public hint executes once');

    my $dynamic = '(??{"A"})';
    ok('A' =~ /^$dynamic$/,
        'public re-eval hint admits a runtime dynamic pattern');

    {
        BEGIN { $^H &= ~0x00200000 }
        my $error = '';
        eval { qr/$runtime/ };
        $error = $@;
        like($error, qr/Eval-group not allowed at runtime/,
            'clearing the exact bit disables admission in a child scope');
    }

    my $outer_error = '';
    my $outer_runtime = '(?{1})';
    eval { qr/$outer_runtime/ };
    $outer_error = $@;
    is($outer_error, '', 'child-scope clearing does not leak outward');
}

{
    my $runtime = '(?{1})';
    my $error = '';
    eval { qr/$runtime/ };
    $error = $@;
    like($error, qr/Eval-group not allowed at runtime/,
        'public hint does not leak after its lexical scope');
}

{
    BEGIN { $^H |= 0x00000001 }
    my $runtime = '(?{1})';
    my $error = '';
    eval { qr/$runtime/ };
    $error = $@;
    like($error, qr/Eval-group not allowed at runtime/,
        'an unrelated hint bit cannot authorize runtime code');
}

{
    use re 'eval';
    my $runtime = '(??{"P"})';
    ok('P' =~ /^$runtime$/,
        'the pragma retains the same runtime admission behavior');
}

is($public_re_eval_hint, 0x00200000,
    'fixture names the public Perl re-eval hint exactly');

{
    my $runtime = '(??{"N"})';
    my $error = '';
    eval { 'N' =~ /^$runtime$/ };
    $error = $@;
    like($error, qr/Eval-group not allowed at runtime/,
        'dynamic source remains rejected without lexical admission');
}
