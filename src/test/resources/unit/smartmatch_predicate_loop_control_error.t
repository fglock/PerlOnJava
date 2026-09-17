use strict;
use warnings;
use Test::More;

for my $operator (qw(last next redo)) {
    my $result = eval "0 ~~ sub { $operator } for 0; 1";
    ok(!defined($result), "$operator cannot escape a smartmatch predicate CV");
    like($@, qr/Can't "$operator" outside a loop block/,
        "$operator reports the loop-boundary error");
}

my $goto_result = eval q{FOO: 0 ~~ sub { goto FOO } for 0; 1};
ok(!defined($goto_result), 'goto cannot escape a smartmatch predicate CV');
like($@, qr/Can't find label FOO/, 'goto reports the missing local label');

done_testing;
