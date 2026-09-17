use strict;
use warnings;
use Test::More;
use feature 'class';
no warnings 'experimental::class';

my $outside = eval q{
    class ClassConstantContext {
        my $name = __CLASS__;
    }
    1;
};
ok(!$outside, '__CLASS__ is rejected in ordinary class-body code');
like($@, qr/Cannot use __CLASS__ outside of a method or field initializer expression/,
    'ordinary class-body __CLASS__ has the Perl diagnostic');

my $method = eval q{
    class ClassConstantMethod {
        method name { __CLASS__ }
    }
    ClassConstantMethod->new->name eq 'ClassConstantMethod';
};
ok($method, '__CLASS__ remains available in a method');

done_testing;
