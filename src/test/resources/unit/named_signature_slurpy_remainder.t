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

plan skip_all => 'named signature parameters are unavailable in this system Perl'
    unless $compiled;

is(positional_remainder(required => 'x', 'tail'), 'x,tail',
    'named parameters may precede a slurpy positional remainder');
is(named_remainder(required => 'x', extra => 'tail'), 'x,extra=tail',
    'named parameters may precede a slurpy named remainder');

done_testing;
