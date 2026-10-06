use strict;
use warnings;
use Test::More;
use Test2::Compare::String;

my $check = Test2::Compare::String->new(input => 'expected');
my $negated = $check->clone_negate;

is($check->operator('actual'), 'eq', 'string comparator uses eq by default');
ok($check->verify(exists => 1, got => 'expected'), 'positive comparison matches');
is($negated->operator('actual'), 'ne', 'negated comparator uses ne');
ok($negated->verify(exists => 1, got => 'actual'), 'negated comparison accepts different string');
ok(!$negated->verify(exists => 1, got => 'expected'), 'negated comparison rejects equal string');

done_testing();
