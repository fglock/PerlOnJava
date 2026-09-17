use strict;
use warnings;
use Test::More;
use feature 'class';
no warnings 'experimental::class';

my $parsed = eval q{class IncompleteConstructorEval { } ; 1};
ok($parsed, 'control class compiles');

my $incomplete = eval 'class IncompleteConstructorEvalBroken {';
ok(!defined $incomplete, 'truncated class declaration fails');

my $new_ok = eval { IncompleteConstructorEvalBroken->new; 1 };
ok(!$new_ok, 'an incomplete eval class has no constructor');
like($@, qr/Can't locate object method "new" via package "IncompleteConstructorEvalBroken"/,
    'incomplete eval class uses missing-method diagnostic');

my $begin_ok = eval q{
    class IncompleteConstructorBegin { BEGIN { IncompleteConstructorBegin->new; } }
    1;
};
ok(!$begin_ok, 'BEGIN cannot construct an incomplete class');
like($@, qr/Cannot create an object of incomplete class "IncompleteConstructorBegin"/,
    'BEGIN construction identifies the incomplete class');

done_testing;
