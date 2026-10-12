use strict;
use warnings;
use Test::More;

sub transform (&$) {
    my ($f, $x) = @_;
    return $f->($x) if $x <= 1;
    return sub { transform($f, $x - 1) }->();
}

my $result = transform { $_[0] + 1 } 2;
is($result, 2,
    'recursive block-prototype call accepts the scalar coderef received by the sub');

done_testing;
