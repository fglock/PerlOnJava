use strict;
use warnings;
use Test::More;

use autobox::Core;

is('hello'->uc, 'HELLO', 'Core methods dispatch on native strings');
is(12->hex, '18', 'Core methods dispatch on native integers');

my @items = qw(red blue green);
is_deeply([@items->elements], \@items,
    'Core methods dispatch on a direct array invocant');
is(@items->size, 3, 'array methods preserve scalar context');
is_deeply([@items->sort], [qw(blue green red)],
    'array methods can chain through their returned reference');

my %pairs = (alpha => 1, beta => 2);
is_deeply([sort { $a cmp $b } %pairs->keys], [qw(alpha beta)],
    'Core methods dispatch on a direct hash invocant');

{
    no autobox::Core qw(SCALAR);
    my $result = eval { 'disabled'->uc };
    like($@, qr/Can't (?:call method|locate object method)/,
        'no autobox::Core disables only the requested native type');
}
is('restored'->uc, 'RESTORED', 'outer Core bindings return after nested no');

done_testing();
