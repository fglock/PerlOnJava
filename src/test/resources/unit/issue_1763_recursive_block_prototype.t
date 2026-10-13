use strict;
use warnings;
use Test::More;

no warnings 'prototype';
sub transform (&$) {
    my ($f, $x) = @_;
    return $f->($x) if $x <= 1;
    return transform($f, $x - 1);
}

my $result = transform { $_[0] + 1 } 2;
is($result, 2,
    'direct recursive call accepts the scalar coderef received by a block prototype');

done_testing;
