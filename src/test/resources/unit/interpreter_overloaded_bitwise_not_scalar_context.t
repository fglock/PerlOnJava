use strict;
use warnings;
use Test::More tests => 3;

{
    package Local::Complement;
    use overload '~' => sub { return bless { value => 42 }, __PACKAGE__ }, fallback => 1;
    sub new { bless {}, shift }
    sub value { $_[0]{value} }
}

my $type = Local::Complement->new;
my $complement = ~$type;
isa_ok($complement, 'Local::Complement',
    'unary bitwise not preserves overloaded object result');
is($complement->value, 42, 'overloaded unary bitwise not returns scalar object');
is(ref(~Local::Complement->new), 'Local::Complement',
    'temporary object follows the same scalar path');
