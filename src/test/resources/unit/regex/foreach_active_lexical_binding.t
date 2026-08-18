use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use re 'eval';
use Test::More;

{
    my $scalar = 42;
    my $saved = \$scalar;
    my (@direct, @callback_values, @callback_refs);

    for \$scalar (\1, \2, \3) {
        push @direct, [$scalar, \$scalar];
        '' =~ /(?{ push @callback_values, $scalar;
                    push @callback_refs, \$scalar })/;
    }

    is_deeply([map { $_->[0] } @direct], [1, 2, 3],
        'scalar refalias exposes each iteration value directly');
    is_deeply([map { ${$_->[1]} } @direct], [1, 2, 3],
        'scalar refalias exposes each iteration cell directly');
    is_deeply(\@callback_values, [1, 2, 3],
        'regex callback sees each rebound scalar value');
    is_deeply([map { $$_ } @callback_refs], [1, 2, 3],
        'regex callback sees each rebound scalar cell');
    is($callback_refs[0], $direct[0][1],
        'scalar callback captures the live first-iteration cell');
    is(\$scalar, $saved, 'scalar lexical cell is restored after foreach');
    is($scalar, 42, 'scalar lexical value is restored after foreach');
}

{
    my @array = (42);
    my $saved = \@array;
    my (@direct, @callback_values, @callback_refs);

    for \@array ([1, 10], [2, 20]) {
        push @direct, [\@array, [@array]];
        '' =~ /(?{ push @callback_values, [@array];
                    push @callback_refs, \@array })/;
    }

    is_deeply([map { $_->[1] } @direct], [[1, 10], [2, 20]],
        'array refalias exposes each iteration value directly');
    is_deeply([map { [@{$_->[0]}] } @direct], [[1, 10], [2, 20]],
        'array refalias exposes each iteration cell directly');
    is_deeply(\@callback_values, [[1, 10], [2, 20]],
        'regex callback sees each rebound array value');
    is_deeply([map { [@$_] } @callback_refs], [[1, 10], [2, 20]],
        'regex callback sees each rebound array cell');
    is($callback_refs[0], $direct[0][0],
        'array callback captures the live first-iteration cell');
    is(\@array, $saved, 'array lexical cell is restored after foreach');
    is_deeply(\@array, [42], 'array lexical value is restored after foreach');
}

{
    my %hash = (k => 42);
    my $saved = \%hash;
    my (@direct, @callback_values, @callback_refs);

    for \%hash ({k => 1}, {k => 2}) {
        push @direct, [\%hash, $hash{k}];
        '' =~ /(?{ push @callback_values, $hash{k};
                    push @callback_refs, \%hash })/;
    }

    is_deeply([map { $_->[1] } @direct], [1, 2],
        'hash refalias exposes each iteration value directly');
    is_deeply([map { {%{$_->[0]}} } @direct], [{k => 1}, {k => 2}],
        'hash refalias exposes each iteration cell directly');
    is_deeply(\@callback_values, [1, 2],
        'regex callback sees each rebound hash value');
    is_deeply([map { {%$_} } @callback_refs], [{k => 1}, {k => 2}],
        'regex callback sees each rebound hash cell');
    is($callback_refs[0], $direct[0][0],
        'hash callback captures the live first-iteration cell');
    is(\%hash, $saved, 'hash lexical cell is restored after foreach');
    is_deeply(\%hash, {k => 42}, 'hash lexical value is restored after foreach');
}

sub {
    foreach (@_) {
        is(eval { \$_ }, \undef,
            'implicit foreach aliases the canonical undef argument cell');
    }
}->(undef);

done_testing;
