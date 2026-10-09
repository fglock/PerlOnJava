use strict;
use warnings;
use Test::More;

# `our` is lexical: a package variable declared in another scope is not visible
# under `strict vars` unless it was imported (assigned into the package from another
# package with a glob reference, or aliased with a whole-glob assignment).

eval 'package Strict::Other; our $DECLARED = 1; 1' or die $@;
my $outside = eval 'package Strict::Other; $DECLARED; 1';
ok !$outside, 'an our variable declared in another lexical scope is not visible';
like $@, qr/not imported|Global symbol/,
    'the strict error names the undeclared package variable';

{
    package Strict::Importer;
    no strict 'refs';
    my $source = 'imported value';
    *{'Strict::Other::IMPORTED'} = \$source;
}
my $imported = eval 'package Strict::Other; my $seen = $IMPORTED; 1';
ok $imported, 'a variable imported by glob assignment from another package is visible';

{
    package Strict::Glob;
    our $ALIASED = 'aliased value';
}
{
    package Strict::Importer2;
    no strict 'refs';
    *{'Strict::Other::ALIASED'} = *{'Strict::Glob::ALIASED'};
}
my $aliased = eval 'package Strict::Other; my $seen = $ALIASED; 1';
ok $aliased, 'a variable imported by whole-glob assignment from another package is visible';

done_testing;
