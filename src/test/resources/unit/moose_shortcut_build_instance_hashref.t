use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require Moose; require MooseX::ShortCut::BuildInstance; 1 }
        or plan skip_all => 'Moose and MooseX::ShortCut::BuildInstance required';
}

my $base = 'DynamicHashRefBuildInstanceRegression::Base';
my $role = 'DynamicHashRefBuildInstanceRegression::Role';
my $package = 'DynamicHashRefBuildInstanceRegression::Built';

my $base_loaded = eval qq{
    package $base;
    use Moose;
    has defined_excel_translations => (
        is => 'ro', isa => 'HashRef', traits => ['Hash'],
        default => sub { { '\@' => 'text' } },
    );
    1;
};
ok($base_loaded, 'Moose base class with HashRef default compiles') or diag($@);

my $role_loaded = eval qq{
    package $role;
    use Moose::Role;
    has role_payload => (is => 'ro', isa => 'HashRef', default => sub { { role => 17 } });
    has _alt_epoch_year => (is => 'ro', isa => 'Int');
    1;
};
ok($role_loaded, 'dynamic Moose role with constructor attributes compiles') or diag($@);

my $payload = { translations => { '@' => 'text' }, values => [1, 2, 3] };
my $instance = MooseX::ShortCut::BuildInstance::build_instance(
    package => $package,
    superclasses => [$base],
    add_roles_in_sequence => [$role],
    add_attributes => {
        payload => { is => 'ro', isa => 'HashRef', required => 1 },
    },
    payload => $payload,
    _alt_epoch_year => 1904,
);

ok($instance, 'BuildInstance constructs a dynamic class with a role');
is_deeply($instance->payload, $payload, 'dynamic constructor preserves HashRef arguments');
is_deeply($instance->defined_excel_translations, { '@' => 'text' },
    'HashRef default survives BuildInstance construction');
is_deeply($instance->role_payload, { role => 17 }, 'role HashRef default survives construction');
is($instance->_alt_epoch_year, 1904, 'dynamic constructor preserves selected scalar arguments');

done_testing;
