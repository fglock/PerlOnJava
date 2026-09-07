use strict;
use warnings;
no strict 'subs';

my $package = 'TestML::Base';
my %values = ($package . ':::E' => 'loaded');

print "1..1\n";
print "ok 1 - a double-colon bareword concatenates inside a braced hash lookup\n"
    if $values{$package.::.':E'} eq 'loaded';
