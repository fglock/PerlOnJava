use strict;
use warnings;
use Test::More;

my $subject = 'foo';
my $outer_reference = \$subject;

for my $study ('', 'study $subject;') {
    my $subject = $subject;
    my ($match, $got);
    my $code = <<"EVAL";
        $study
        pos(\$subject) = 0;
        \$match = (\$subject =~ m'(?=foo)'g);
        \$got = pos(\$subject);
EVAL
    eval $code;
    is $@, '', 'dynamic eval succeeds with a referenced outer lexical';
    is $match, 1, 'dynamic eval matches through the shadowing lexical';
    is $got, 0, 'dynamic eval retains the shadowing lexical position';
}

is $subject, 'foo', 'the outer referenced lexical remains unchanged';
is $$outer_reference, 'foo', 'the outer reference retains its target';

my $outer_eval = eval q{
    $subject = 'outer-eval';
    $subject;
};
is $@, '', 'dynamic eval succeeds after the shadowing lexical exits';
is $outer_eval, 'outer-eval', 'dynamic eval resolves the outer lexical after scope cleanup';
is $$outer_reference, 'outer-eval', 'the outer reference observes the post-cleanup eval assignment';

done_testing;
