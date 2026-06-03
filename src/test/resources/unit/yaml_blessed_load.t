#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use Scalar::Util qw(blessed);

eval { require YAML; 1 }
    or plan skip_all => 'YAML required';
plan tests => 2;

my $yaml = "--- !!perl/hash:YamlBlessedLoad::Thing\nname: example\n";

my $object = YAML::Load($yaml);
is(blessed($object), undef, 'YAML does not load perl/hash tags as blessed hashes by default');

{
    local $YAML::LoadBlessed = 0;
    my $plain = YAML::Load($yaml);
    is(blessed($plain), undef, 'YAML::LoadBlessed can disable blessed loading');
}
