use strict;
use warnings;
use Test::More;

{
    package TieOperatorSelf;
    sub TIEHASH { bless $_[1], $_[0] }
}

my $self_tie_error = eval {
    my %hash;
    tie %hash, 'TieOperatorSelf', \%hash;
    1;
} ? '' : $@;
like $self_tie_error, qr/Self-ties of arrays and hashes are not supported/,
    'aggregate self-ties are rejected';

for my $case (
    [q{my $value; tie $value, ''}, 'main'],
    [q{my $value; tie $value, undef}, 'main'],
    [q{my $value; tie $value, *NoSuchTiePackage}, 'NoSuchTiePackage'],
) {
    my ($code, $package) = @$case;
    my $error = eval $code;
    $error = $@;
    like $error, qr/Can't locate object method "TIESCALAR" via package "\Q$package\E"/,
        "tie reports the canonical package name for $package";
unlike $error, qr/perhaps you forgot to load/,
        'non-string tie class arguments omit the load hint';
}

{
    package TieOperatorFetchUntie;
    sub TIESCALAR { bless $_[1], $_[0] }
    sub FETCH { $_[0]->() }
}

my $fetch_untie;
tie $fetch_untie, 'TieOperatorFetchUntie', sub { untie $fetch_untie; 42 };
is "$fetch_untie/$fetch_untie", '42/42',
    'FETCH that unties its scalar leaves the fetched value behind';

{
    package TieOperatorOverloadedValue;
    use overload
        '0+' => sub { print '0+'; 1 },
        '+'  => sub { print '+';  1 },
        '*'  => sub { print '*';  1 },
        '<'  => sub { print '<';  1 },
        '<>' => sub { print '<>'; undef },
        '@{}' => sub { print '@{}'; [] },
        '%{}' => sub { print '%{}'; {} },
        '${}' => sub { print '${}'; \(my $value) },
        '*{}' => sub { print '*{}'; \*STDOUT };
}
{
    package TieOperatorOverloaded;
    our $value = bless [], 'TieOperatorOverloadedValue';
    sub TIESCALAR { bless {}, shift }
    sub FETCH { $value }
    sub STORE {}
}

tie my $overloaded, 'TieOperatorOverloaded';
my $overload_output = '';
{
    open my $capture, '>', \$overload_output or die $!;
    local *STDOUT = $capture;
    1 + $overloaded;
    $overloaded * 1;
    $overloaded < 1;
    <$overloaded>;
    @$overloaded;
    %$overloaded;
    $$overloaded;
    *$overloaded;
}
is $overload_output, '+*<<>@{}%{}${}*{}',
    'operators and void dereferences dispatch directly to an object returned by tied FETCH';

{
    package TieOperatorSeparator;
    our $fetches = 0;
    sub TIESCALAR { bless \(my $value), shift }
    sub STORE { ${ $_[0] } = $_[1] }
    sub FETCH { $fetches++; print '<FETCH>'; ${ $_[0] } }
}

my $separator_output = '';
my ($separator_fetches_after_store, $separator_fetches_after_print);
{
    # Keep assertions outside the tied $, scope: Test::More's own output
    # would otherwise exercise the separator.
    my $setup_output = '';
    {
        open my $capture, '>', \$setup_output or die $!;
        local *STDOUT = $capture;
        tie $,, 'TieOperatorSeparator';
        $, = '::';
        $separator_fetches_after_store = $TieOperatorSeparator::fetches;
    }
    {
        open my $capture, '>', \$separator_output or die $!;
        local *STDOUT = $capture;
        print 'left', 'right';
        $separator_fetches_after_print = $TieOperatorSeparator::fetches;
    }
    untie $,;
}
is $separator_fetches_after_store, 0,
    'storing to tied output field separator does not fetch it';
is $separator_output, 'left<FETCH>::right',
    'print writes each argument before fetching a tied output separator';
is $separator_fetches_after_print, 1,
    'print fetches the tied output field separator exactly once between two arguments';

{
    package TieOperatorGlobCopy;
    sub TIESCALAR { bless [], shift }
    sub TIEHANDLE { bless [], shift }
}

{
    my $glob_copy = *TieOperatorGlobCopyTarget;
    tie *$glob_copy, 'TieOperatorGlobCopy';
    tie $glob_copy, 'TieOperatorGlobCopy';
    untie $glob_copy;
    ok !defined tied $glob_copy,
        'untie removes scalar magic from a glob copy without exposing its tied handle';
    untie *$glob_copy;
    ok !defined tied *TieOperatorGlobCopyTarget,
        'untie through a glob copy removes the original glob handle tie';
}

{
    package TieOperatorDeferred;
    sub TIESCALAR { bless [], shift }
}

{
    my %hash;
    sub {
        tie $_[0], 'TieOperatorDeferred';
        is ref tied $hash{tied_by_alias}, 'TieOperatorDeferred',
            'tied resolves an existing deferred alias';
        tie $hash{tied_directly}, 'TieOperatorDeferred';
        is ref tied $_[1], 'TieOperatorDeferred',
            'tied resolves an existing direct hash element through its alias';
        untie $hash{tied_by_alias};
        ok !defined tied $_[0], 'untie removes a tied deferred alias';
        untie $_[1];
        ok !defined tied $hash{tied_directly}, 'untie removes a tied direct element through its alias';
        tied $_[2];
        ok !exists $hash{missing}, 'tied does not vivify a deferred missing hash element';
        untie $_[2];
        ok !exists $hash{missing}, 'untie does not vivify a deferred missing hash element';
    }->($hash{tied_by_alias}, $hash{tied_directly}, $hash{missing});
}

{
    package TieOperatorKeysBoolean;
    our ($first, $next, $scalar) = (0, 0, 0);
    sub TIEHASH { bless { data => { a => 1, b => 2 } }, shift }
    sub FIRSTKEY { $first++; my $n = keys %{ $_[0]{data} }; each %{ $_[0]{data} } }
    sub NEXTKEY { $next++; each %{ $_[0]{data} } }
    sub SCALAR { $scalar++; scalar %{ $_[0]{data} } }
}

{
    tie my %hash, 'TieOperatorKeysBoolean';
    ok !(!keys %hash), 'boolean keys reports a non-empty tied hash';
    is "$TieOperatorKeysBoolean::first/$TieOperatorKeysBoolean::next/$TieOperatorKeysBoolean::scalar", '0/0/1',
        'boolean keys dispatches SCALAR instead of iterating tied keys';
}

{
    package TieOperatorIteratorBoolean;
    our $first = 0;
    sub TIEHASH { bless { data => { a => 1 } }, shift }
    sub FIRSTKEY { $first++; my $n = keys %{ $_[0]{data} }; each %{ $_[0]{data} } }
    sub NEXTKEY { each %{ $_[0]{data} } }
    sub FETCH { $_[0]{data}{$_[1]} }
}

{
    tie my %hash, 'TieOperatorIteratorBoolean';
    my ($key, $value) = each %hash;
    ok $hash{a}, 'tied hash remains readable during each iteration';
    ok %hash, 'boolean tied hash stays true during each iteration';
    is $TieOperatorIteratorBoolean::first, 1,
        'boolean tied hash does not restart FIRSTKEY during each iteration';
}

{
    no warnings 'experimental::builtin';
    use builtin 'weaken';
    package TieOperatorBuiltinScope;
    sub weaken_after_package {
        my $value = {};
        my $strong = $value;
        weaken($value);
        $strong;
    }
    package main;
    ok ref TieOperatorBuiltinScope::weaken_after_package(),
        'explicit builtin weaken remains lexical after a package declaration';
}

done_testing;
