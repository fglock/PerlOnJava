use strict;
use warnings;
use Test::More;

use constant STASH_CONSTANT => 1;

my $before = \&STASH_CONSTANT;
{
    no strict 'refs';
    ${'main::STASH_CONSTANT'} = ${'main::STASH_CONSTANT'};
}
my $after = \&STASH_CONSTANT;

is($after, $before, 'stash self-assignment preserves constant CODE identity');
is($after->(), 1, 'preserved constant remains callable');

done_testing;
