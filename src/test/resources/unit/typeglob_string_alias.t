use strict;
use warnings;
no warnings 'once';
use Test::More tests => 4;

our $source = 7;
our $alias = 3;

{
    local *alias = 'main::source';
    is($alias, 7, 'string typeglob assignment aliases the scalar slot');
    $alias = 8;
    is($source, 8, 'writes through the string typeglob alias');
}
is($alias, 3, 'local string typeglob assignment restores the old glob');

my $payload = "payload\n";
open SOURCE_HANDLE, '<', \$payload or die "open scalar handle: $!";
{
    local *BORROWED_HANDLE = 'main::SOURCE_HANDLE';
    is(scalar <BORROWED_HANDLE>, "payload\n",
        'string typeglob assignment aliases the IO slot');
}
