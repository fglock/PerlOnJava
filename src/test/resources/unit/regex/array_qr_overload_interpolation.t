use strict;
use warnings;
use Test::More tests => 7;

{
    package Local::ArrayQrOverload;
    use overload
        'qr' => sub { qr/a/ },
        '""' => sub { 'string-form' };
}

my $object = bless [], 'Local::ArrayQrOverload';
my @one = ($object);
my @two = ($object, $object);

ok('a' =~ /^$object$/, 'scalar regex interpolation dispatches qr overload');
ok('a' =~ /^@one$/, 'array regex interpolation dispatches qr overload');
ok('a a' =~ /^@two$/, 'every array element dispatches qr overload');
{
    local $" = 'b';
    ok('aba' =~ /^@two$/, 'regex array interpolation retains the list separator');
}

is("@one", 'string-form', 'ordinary single-element array stringification is unchanged');
is("@two", 'string-form string-form',
    'ordinary multi-element array stringification is unchanged');
is("x@one", 'xstring-form', 'ordinary mixed string interpolation is unchanged');
