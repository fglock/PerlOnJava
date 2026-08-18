use strict;
use warnings;
use Test::More;

{
    package CountingScalar;
    our ($fetches, $stores);

    sub TIESCALAR {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }

    sub FETCH {
        ++$fetches;
        return $_[0]{value};
    }

    sub STORE {
        ++$stores;
        $_[0]{value} = $_[1];
    }

    sub reset {
        $fetches = 0;
        $stores = 0;
    }
}

for my $case (
    ['string', 'left'],
    ['reference', \1],
    ['glob', *STDOUT],
) {
    my ($name, $initial) = @$case;
    tie my $value, 'CountingScalar', $initial;
    CountingScalar::reset();
    $value .= 'x';
    is($CountingScalar::fetches, 1,
        "$name concat assignment fetches the tied lhs once");
    is($CountingScalar::stores, 1,
        "$name concat assignment stores the tied lhs once");
}

tie my $both, 'CountingScalar', 'a';
CountingScalar::reset();
my $result = ($both .= $both);
is($result, 'aa', 'concat assignment with tied lhs and rhs returns the result');
is($CountingScalar::fetches, 3,
    'concat assignment with the same tied lhs and rhs fetches three times');
is($CountingScalar::stores, 1,
    'concat assignment with the same tied lhs and rhs stores once');

tie my $pattern, 'CountingScalar', 'needle';
CountingScalar::reset();
ok('a needle here' =~ /$pattern/, 'tied interpolation supplies the regex text');
is($CountingScalar::fetches, 1, 'regex interpolation fetches the tied scalar once');

done_testing;
