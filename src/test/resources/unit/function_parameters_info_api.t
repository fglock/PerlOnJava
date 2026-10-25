use strict;
use warnings;
use Test::More;

BEGIN { unshift @INC, 'src/main/perl/lib' }
use Function::Parameters::Info;

my $self = bless { name => '$self', type => undef }, 'Function::Parameters::Info::Parameter';
my $required = bless { name => '$x', type => undef }, 'Function::Parameters::Info::Parameter';
my $optional = bless { name => '$y', type => undef }, 'Function::Parameters::Info::Parameter';
my $info = bless {
    invocants => [$self],
    positional_required => [$required],
    positional_optional => [$optional],
    named_required => [],
    named_optional => [],
    slurpy => undef,
    args_min => 2,
    args_max => 3,
}, 'Function::Parameters::Info';

is("$required", '$x', 'parameter objects stringify to their names');
is($required->name, '$x', 'parameter objects expose their names');
ok(!defined $required->type, 'parameter objects expose an absent type as undef');
is_deeply([$info->invocants], [$self], 'info returns invocant objects in list context');
is(scalar $info->invocants, 1, 'info returns an invocant count in scalar context');
is("" . $info->invocant, '$self', 'info returns its single invocant');
is_deeply([$info->positional_required], [$required], 'info returns required positional objects');
is_deeply([$info->positional_optional], [$optional], 'info returns optional positional objects');
is(scalar $info->named_required, 0, 'info returns a named parameter count in scalar context');
ok(!defined $info->slurpy, 'info returns undef when the signature is not slurpy');
is($info->args_min, 2, 'info exposes the minimum argument count');
is($info->args_max, 3, 'info exposes the maximum argument count');

done_testing;
