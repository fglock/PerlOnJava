use strict;
use warnings;
use feature 'state';
use Scalar::Util qw(weaken);
use Test::More;
our $initialized = 0;
sub pinned_metadata { state $metadata = { serial => ++$initialized }; $metadata }
my $weak = pinned_metadata(); weaken($weak);
delete $main::{pinned_metadata};
ok(defined $weak, 'compiled GV owner preserves state after stash name deletion');
is(pinned_metadata()->{serial}, 1, 'compiled call uses original state after stash deletion');
is($initialized, 1, 'stash deletion does not initialize replacement state');
ok(defined $weak, 'original state remains owned by the compiled call site');
done_testing();
