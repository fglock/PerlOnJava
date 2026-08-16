use strict;
use warnings;
use Test::More tests => 13;

{
    package FalseObject;
    our $BOOL_CALLS = 0;
    use overload
        bool => sub { $BOOL_CALLS++; 0 },
        '0+' => sub { 0 },
        '""' => sub { '0' },
        fallback => 1;

    sub new { bless {}, shift }
}

my $object = FalseObject->new;
ok(!$object, 'bool overload is active normally');
is($FalseObject::BOOL_CALLS, 1, 'normal logical not invokes bool overload');

{
    no overloading;
    is(!$object, '', 'logical not ignores bool overload');
    is($FalseObject::BOOL_CALLS, 1, 'disabled logical not does not invoke overload');
    ok($object ? 1 : 0, 'conditional truth ignores bool overload');
    is($object ? 'yes' : 'no', 'yes', 'ternary condition ignores bool overload');
    is($object && 'rhs', 'rhs', 'logical and uses raw reference truth');
    is($object || 'rhs', $object, 'logical or uses raw reference truth');
    my $assigned = $object;
    $assigned ||= 'rhs';
    is($assigned, $object, 'logical assignment uses raw reference truth');
    is($FalseObject::BOOL_CALLS, 1, 'disabled conditions do not invoke overload');

    {
        use overloading;
        ok(!$object, 'use overloading restores dispatch in nested scope');
    }

    is(!$object, '', 'no overloading remains active after nested scope');
}

ok(!$object, 'overloading is restored outside lexical scope');
