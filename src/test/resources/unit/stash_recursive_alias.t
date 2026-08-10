use strict;
use warnings;
use Test::More;

package Acme::Meta;

our $VERSION;

BEGIN {
    $::Meta::VERSION = $VERSION = 0;
    $Meta::{'Meta::'} = $main::{'Meta::'};
    $Acme::Meta::{'Meta::'} = $main::{'Meta::'};
}

$Acme::Meta::Meta::Pie = 'good';

package main;

is(
    $Acme::Meta::Meta::Meta::Meta::Pie,
    'good',
    'recursive package-stash aliases resolve to the shared symbol table',
);

done_testing;
