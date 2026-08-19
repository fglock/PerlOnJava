use strict;
use warnings;
use Test::More tests => 12;

our ($count_a, $count_b);

for my $case (
    [ 'C?',       qr/(A (.*) (?{ $count_a++ }) C?       (*THEN) | A D) (*FAIL)/x,
                  qr/(A (.*) (?{ $count_b++ }) C?       (*THEN) | A D) z/x ],
    [ 'C? tail',  qr/(A (.*) (?{ $count_a++ }) C?       (*THEN) | A D) \s* (*FAIL)/x,
                  qr/(A (.*) (?{ $count_b++ }) C?       (*THEN) | A D) \s* z/x ],
    [ 'C or nil', qr/(A (.*) (?{ $count_a++ }) (?:C|)   (*THEN) | A D) (*FAIL)/x,
                  qr/(A (.*) (?{ $count_b++ }) (?:C|)   (*THEN) | A D) z/x ],
    [ 'C range',  qr/(A (.*) (?{ $count_a++ }) C{0,6}   (*THEN) | A D) (*FAIL)/x,
                  qr/(A (.*) (?{ $count_b++ }) C{0,6}   (*THEN) | A D) z/x ],
    [ 'CE range', qr/(A (.*) (?{ $count_a++ }) (CE){0,6} (*THEN) | A D) (*FAIL)/x,
                  qr/(A (.*) (?{ $count_b++ }) (CE){0,6} (*THEN) | A D) z/x,
                  'AbcdCEBefgBhiBqz' ],
    [ 'CE* range',qr/(A (.*) (?{ $count_a++ }) (CE*){0,6} (*THEN) | A D) (*FAIL)/x,
                  qr/(A (.*) (?{ $count_b++ }) (CE*){0,6} (*THEN) | A D) z/x ],
) {
    my ($name, $fail_pattern, $tail_pattern, $subject) = @$case;
    $subject //= 'AbcdCBefgBhiBqz';
    $count_a = $count_b = 0;
    $subject =~ $fail_pattern;
    $subject =~ $tail_pattern;
    is($count_a, 1, "$name cuts quantifier retries before a forced failure");
    is($count_b, 1, "$name cuts quantifier retries before a tail mismatch");
}
