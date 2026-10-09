use strict;
use warnings FATAL => 'all';
use Test::More;

# Whitespace inside a prototype is not part of the prototype, so spacing
# around the characters must not trigger the "after '@'" or "after '_'" checks.
sub spaced_slurpy ( $$;$@ ) { return 'spaced' }
sub plain_slurpy($$;$@) { return 'plain' }
is spaced_slurpy(1, 2), 'spaced',
    'a spaced prototype ending in @ compiles under FATAL warnings';
is plain_slurpy(1, 2), 'plain',
    'an unspaced prototype ending in @ compiles under FATAL warnings';

sub spaced_underscore ( _ ; $ ) { return $_[0] // 'undef' }
is spaced_underscore(7), 7,
    'a spaced prototype with _ before ; compiles under FATAL warnings';

done_testing;
