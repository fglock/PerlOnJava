#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(blessed);
use YAML ();

my $yaml = "--- !!perl/hash:YamlBlessedLoad::Thing\nname: example\n";

my $object = YAML::Load($yaml);
is(blessed($object), 'YamlBlessedLoad::Thing', 'YAML loads perl/hash tags as blessed hashes by default');

{
    local $YAML::LoadBlessed = 0;
    my $plain = YAML::Load($yaml);
    is(blessed($plain), undef, 'YAML::LoadBlessed can disable blessed loading');
}
