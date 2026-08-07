#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

BEGIN {
    plan skip_all => 'tests the PerlOnJava Exporter::Lexical backend'
        unless $^X eq 'jperl' || $^X =~ m{(?:^|[\\/])jperl(?:\.bat)?$};
}

use lib 'src/test/resources/unit/lib';
use Exporter::Lexical ();

sub word { 'package' }

is(word(), 'package', 'package sub is initially visible');
{
    BEGIN {
        Exporter::Lexical::lexical_import(word => sub { 'lexical' });
    }
    is(word(), 'lexical', 'BEGIN import mutates the enclosing compiler scope');
}
is(word(), 'package', 'lexical import ends with its scope');

done_testing;
