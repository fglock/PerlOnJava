use strict;
use warnings;
use Test::More tests => 4;

local our $lower = 'i';
local our $upper = 'J';

eval { 'Ia' =~ /^\U(??{"$lower\Ea"})$/ };
like($@, qr/Eval-group not allowed at runtime/,
    'uppercase interpolation requires runtime eval permission');

eval { 'ja' =~ /^\L(??{"$upper\Ea"})$/ };
like($@, qr/Eval-group not allowed at runtime/,
    'lowercase interpolation requires runtime eval permission');

{
    use re 'eval';
    ok('Ia' =~ /^\U(??{"$lower\Ea"})$/,
        'uppercase interpolation executes after runtime permission');
    ok('ja' =~ /^\L(??{"$upper\Ea"})$/,
        'lowercase interpolation executes after runtime permission');
}
