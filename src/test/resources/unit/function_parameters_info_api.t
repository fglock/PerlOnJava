use strict;
use warnings;
use Test::More;

BEGIN { unshift @INC, 'src/main/perl/lib' }
use Function::Parameters::Info;

my $self = bless { name => '$self', type => undef }, 'Function::Parameters::Param';
my $required = bless { name => '$x', type => undef }, 'Function::Parameters::Param';
my $optional = bless { name => '$y', type => undef }, 'Function::Parameters::Param';
my $info = bless {
    keyword => 'method',
    nshift => 1,
    _positional_required => [$self, $required],
    _positional_optional => [$optional],
    _named_required => [],
    _named_optional => [],
    slurpy => undef,
    args_min => 2,
    args_max => 3,
}, 'Function::Parameters::Info';

is("$required", '$x', 'parameter objects stringify to their names');
is($required->name, '$x', 'parameter objects expose their names');
ok(!defined $required->type, 'parameter objects expose an absent type as undef');
is($info->keyword, 'method', 'info reports the declaration keyword');
is($info->nshift, 1, 'info reports the invocant count');
is_deeply([$info->invocants], [$self], 'info returns invocant objects in list context');
is(scalar $info->invocants, 1, 'info returns an invocant count in scalar context');
is("" . $info->invocant, '$self', 'info returns its single invocant');
is_deeply([$info->positional_required], [$required], 'info returns required positional objects');
is_deeply([$info->positional_optional], [$optional], 'info returns optional positional objects');
is(scalar $info->named_required, 0, 'info returns a named parameter count in scalar context');
ok(!defined $info->slurpy, 'info returns undef when the signature is not slurpy');
is($info->args_min, 2, 'info exposes the minimum argument count');
is($info->args_max, 3, 'info exposes the maximum argument count including the invocant');

my $two_invocants = bless {
    nshift => 2,
    _positional_required => [$self, $required],
    _positional_optional => [],
    _named_required => [],
    _named_optional => [],
}, 'Function::Parameters::Info';
my $single_invocant_error = eval { $two_invocants->invocant; 1 };
ok(!$single_invocant_error, 'invocant rejects a multi-invocant signature');
like($@, qr/Can't return a single invocant/, 'multi-invocant diagnostic is clear');

done_testing;
