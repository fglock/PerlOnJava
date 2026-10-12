use strict;
use warnings;
use Test::More tests => 3;

my $schema = { required => ['name'] };
my $properties = $schema->{properties};
ok(!defined $properties, 'reading absent properties returns undef');
ok(!exists $schema->{properties}, 'reading absent properties does not autovivify the schema');

my $nested = {
    oneOf => [
        { properties => { name => { type => 'string' } } },
        { properties => { id => { type => 'integer' } } },
    ],
};
is($nested->{oneOf}[0]{properties}{name}{type}, 'string',
    'nested properties remain accessible inside a oneOf schema');
