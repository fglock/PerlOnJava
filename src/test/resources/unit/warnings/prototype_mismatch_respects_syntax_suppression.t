use strict;
use warnings;
use Test::More;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $ok = eval q{
        package PrototypeSyntaxSuppression;
        use Scalar::Util qw(set_prototype);
        use warnings;
        no warnings 'syntax';
        BEGIN { set_prototype \&generated, '_' }
        sub generated { $_[0] }
        1;
    };
    ok($ok, 'prototype can be set before the subroutine definition');
    is($@, '', 'prototype setup compiles without an error');
}

is_deeply(\@warnings, [], 'syntax warning suppression hides prototype mismatch');

done_testing;
