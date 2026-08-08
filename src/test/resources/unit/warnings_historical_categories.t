use strict;
use warnings;
use Test::More tests => 1;

{
    no warnings 'experimental::postderef';
    pass('historical experimental::postderef category remains accepted');
}
