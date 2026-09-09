use strict;
use warnings;
use Test::More tests => 4;

my %hash = (bare => 'value', quoted => 'text');
is($hash{bare}, 'value', 'bareword constant key fetch');
is($hash{'quoted'}, 'text', 'quoted constant key fetch');
$hash{bare} = 'changed';
is($hash{bare}, 'changed', 'constant key remains a writable hash lvalue');
{
    local $hash{bare} = 'local';
    is($hash{bare}, 'local', 'local constant key retains proxy behavior');
}
