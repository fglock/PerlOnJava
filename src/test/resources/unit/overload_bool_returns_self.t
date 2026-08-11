use strict;
use warnings;
use Test::More tests => 2;

{
    package BoolReturnsSelf;

    use overload bool => sub { $_[0] };

    sub new { bless {}, shift }
}

my $object = BoolReturnsSelf->new;
ok($object, 'a boolean overload may return the object itself');
is(!!$object, 1, 'self-returning bool overload is stable across negation');
