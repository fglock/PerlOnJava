use strict;
use warnings;
use Test::More;

{
    package Feature::Fc::Conditional;
    use if $] >= 5.016000, feature => 'fc';

    sub nocase {
        my $text = shift;
        if ($] >= 5.016000) {
            $text = fc $text;
        } else {
            $text = lc $text;
        }
        return $text;
    }
}

is(Feature::Fc::Conditional::nocase('Frognerseteren'), 'frognerseteren',
    'bare unary fc on an assignment RHS remains a case-folding operator');

done_testing;
