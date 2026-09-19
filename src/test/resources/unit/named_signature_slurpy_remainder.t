use Test::More;

my $compiled = eval q{
    use feature 'signatures';
    no warnings 'experimental::signature_named_parameters';

    sub positional_remainder (:$required, @rest) {
        return join q{,}, $required, @rest;
    }

    sub named_remainder (:$required, %rest) {
        return join q{,}, $required, map { "$_=$rest{$_}" } sort keys %rest;
    }
    1;
};

SKIP: {
    skip 'named signature parameters are unavailable in this system Perl', 2
        unless $compiled;
    is(positional_remainder(required => 'x', 'tail'), 'x,tail',
        'named parameters may precede a slurpy positional remainder');
    is(named_remainder(required => 'x', extra => 'tail'), 'x,extra=tail',
        'named parameters may precede a slurpy named remainder');
}

my $any_compiled = eval q{
    use feature 'keyword_any';
    no warnings 'experimental::keyword_any';
    sub parenthesized_any { any( { $_ > 2 } @_ ) }
    1;
};
SKIP: {
    skip 'keyword_any is unavailable in this system Perl', 1 unless $any_compiled;
    ok(parenthesized_any(1, 3), 'parenthesized any invocation accepts a literal block');
}

done_testing;
