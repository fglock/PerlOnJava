use strict;
use warnings;
use feature 'class';
no warnings 'experimental::class';
use Test::More tests => 1;

package RefcountedFieldValue {
    sub new { bless {}, shift }
}

class RefcountedFieldOwner {
    field $value;
    ADJUST { $value = RefcountedFieldValue->new }
    method value { return $value }
}

package main;

my $owner = RefcountedFieldOwner->new;
my $actual = &Internals::SvREFCNT($owner->value) + 1;
is($actual, 2, 'returned field value includes the method-result reference');
