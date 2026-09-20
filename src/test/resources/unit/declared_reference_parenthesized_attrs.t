use strict;
use warnings;
use Test::More;

use feature qw(declared_refs state);
no warnings qw(experimental::declared_refs);

our %attribute_calls;

sub MODIFY_SCALAR_ATTRIBUTES {
    $attribute_calls{scalar}++;
    return;
}

sub MODIFY_ARRAY_ATTRIBUTES {
    $attribute_calls{array}++;
    return;
}

sub MODIFY_HASH_ATTRIBUTES {
    $attribute_calls{hash}++;
    return;
}

for my $declarator (qw(my state)) {
    for my $sigil (qw($ @ %)) {
        my $source = "${declarator} (\\${sigil}value) : parenthesized_attr; 1";
        my $result = eval $source;
        is($@, '', "$declarator parenthesized declared reference compiles for $sigil");
        ok($result, "$declarator parenthesized declared reference evaluates for $sigil");
    }
}

is($attribute_calls{scalar}, 2, 'scalar attribute handler runs for my and state');
is($attribute_calls{array}, 2, 'array attribute handler runs for my and state');
is($attribute_calls{hash}, 2, 'hash attribute handler runs for my and state');

done_testing;
