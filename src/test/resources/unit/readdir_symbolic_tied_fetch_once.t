use strict;
use warnings;
use Test::More tests => 2;
{
    package SymbolicDirectory;
    our $fetches = 0;
    sub TIESCALAR { bless [], shift }
    sub FETCH { ++$fetches; 988 }
}
tie my $directory, 'SymbolicDirectory';
{
    no warnings;
    no strict 'refs';
    readdir $directory;
}
is($SymbolicDirectory::fetches, 1, 'invalid symbolic readdir fetches its tied operand once');
$SymbolicDirectory::fetches = 0;
{
    no warnings;
    no strict 'refs';
    my @entries = readdir $directory;
}
is($SymbolicDirectory::fetches, 1, 'list-context symbolic readdir also fetches once');
