use strict;
use warnings;
use Test::More tests => 3;

$_ = 'caller topic';

sub first_topic {
    foreach (@_) {
        return $_;
    }
    return undef;
}

is(first_topic('first', 'second'), 'first',
    'return receives the implicit foreach topic');
is($_, 'caller topic',
    'return from implicit foreach restores the caller topic');
is(first_topic(), undef, 'empty implicit foreach returns undef without changing topic');
