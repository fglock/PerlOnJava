use strict;
use warnings;
use Test::More tests => 7;

my %state = (pad => 'outer');
{
    local($state{pad}) = $state{pad};
    is($state{pad}, 'outer',
        'parenthesized local hash element keeps its assigned value');
    $state{pad} .= '-inner';
    is($state{pad}, 'outer-inner',
        'localized hash element remains an assignable container entry');
}
is($state{pad}, 'outer',
    'parenthesized local hash element restores its previous value');

my %direct = (pad => 'before');
{
    local $direct{pad} = $direct{pad};
    is($direct{pad}, 'before',
        'direct local hash element RHS sees the outer value');
}
is($direct{pad}, 'before',
    'direct local hash element restores its previous value');

my @array = ('before');
{
    local $array[0] = $array[0];
    is($array[0], 'before',
        'local array element RHS sees the outer value');
}
is($array[0], 'before',
    'local array element restores its previous value');
