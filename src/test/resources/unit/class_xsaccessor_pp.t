use strict;
use warnings;
use Test::More;

eval {
    require Class::XSAccessor;
    require Class::XSAccessor::Array;
    1;
} or plan skip_all => 'Class::XSAccessor and Class::XSAccessor::Array required';

ok(defined Class::XSAccessor->VERSION, 'Class::XSAccessor version is available');
ok(Class::XSAccessor->can('__entersub_optimized__'), 'XS entersub optimizer probe is available');

package XSAHash;
Class::XSAccessor->import(
    constructor        => 'new',
    accessors          => { foo => 'foo' },
    getters            => { get_bar => 'bar' },
    setters            => { set_bar => 'bar' },
    predicates         => { has_bar => 'bar' },
    exists_predicates  => { has_baz => 'baz' },
    lvalue_accessors   => { lv => 'lv' },
    true               => [ 'always_true' ],
    false              => [ 'always_false' ],
);

package XSAArray;
Class::XSAccessor::Array->import(
    constructor      => 'new',
    accessors        => { foo => 0 },
    getters          => { get_bar => 1 },
    setters          => { set_bar => 1 },
    predicates       => { has_bar => 1 },
    lvalue_accessors => { lv => 2 },
);

package main;

my $hash = XSAHash->new(foo => 'a', bar => undef);
isa_ok($hash, 'XSAHash');
is($hash->foo, 'a', 'hash accessor reads');
is($hash->foo('b'), 'b', 'hash accessor writes');
is($hash->foo, 'b', 'hash accessor returns new value');
is($hash->set_bar('c'), 'c', 'hash setter writes');
is($hash->get_bar, 'c', 'hash getter reads');
ok($hash->has_bar, 'defined predicate sees defined value');
$hash->set_bar(undef);
ok(!$hash->has_bar, 'defined predicate rejects undef');
ok(!$hash->has_baz, 'exists predicate rejects missing key');
$hash->{baz} = undef;
ok($hash->has_baz, 'exists predicate accepts undef value');
$hash->lv = 42;
is($hash->lv, 42, 'hash lvalue accessor writes');
ok($hash->always_true, 'true constant accessor');
ok(!$hash->always_false, 'false constant accessor');

my $array = XSAArray->new(foo => 'ignored');
isa_ok($array, 'XSAArray');
ok(!defined $array->foo, 'array constructor ignores arguments');
is($array->foo('x'), 'x', 'array accessor writes');
is($array->foo, 'x', 'array accessor reads');
ok(!$array->has_bar, 'array predicate rejects undef');
is($array->set_bar('y'), 'y', 'array setter writes');
is($array->get_bar, 'y', 'array getter reads');
ok($array->has_bar, 'array predicate sees defined value');
$array->lv = 84;
is($array->lv, 84, 'array lvalue accessor writes');

my $ok = eval { XSAHash->foo; 1 };
ok(!$ok, 'hash accessor rejects class invocant');
like($@, qr/Class::XSAccessor: invalid instance method invocant: no hash ref supplied/,
    'hash bad invocant error');

$ok = eval { XSAArray->foo; 1 };
ok(!$ok, 'array accessor rejects class invocant');
like($@, qr/Class::XSAccessor: invalid instance method invocant: no array ref supplied/,
    'array bad invocant error');

$ok = eval { XSAHash::foo(); 1 };
ok(!$ok, 'hash accessor rejects missing invocant');
like($@, qr/Usage: (?:XSAHash::)?foo\(self, \.\.\.\)/, 'hash missing invocant usage');

done_testing();
