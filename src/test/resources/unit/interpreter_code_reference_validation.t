use strict;
use warnings;
use Test::More tests => 5;

{
    package InterpreterCodeReference::PlainObject;
    sub new { bless {} }
}

{
    package InterpreterCodeReference::CallableObject;
    use overload '&{}' => sub { sub { 42 } }, fallback => 1;
    sub new { bless {} }
}

my $plain = InterpreterCodeReference::PlainObject->new;
my $plain_ok = eval { my $code = \&$plain; 1 };
ok(!$plain_ok, 'non-callable blessed object is rejected as a code reference');
like($@, qr/Not a (?:subroutine|CODE) reference/,
    'non-callable blessed object reports a code-reference error');

my $hash = {};
my $hash_ok = eval { my $code = \&$hash; 1 };
ok(!$hash_ok, 'unblessed hash reference is rejected as a code reference');

my $callable = InterpreterCodeReference::CallableObject->new;
my $code = eval { \&$callable };
is(ref($code), 'CODE', 'callable overloaded object produces a code reference');
is($code->(), 42, 'overloaded code reference remains callable');
