use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require Moose; 1 } or plan skip_all => 'Moose required';
}

my $package = 'DynamicHashRefConstructorRegression';
my $base = 'DynamicHashRefConstructorRegression::Base';
my $role = 'DynamicHashRefConstructorRegression::Role';
my $second_role = 'DynamicHashRefConstructorRegression::SecondRole';
my $base_loaded = eval qq{
    package $base;
    use Moose;
    has defined_excel_translations => (is => 'ro', isa => 'HashRef', traits => ['Hash'], default => sub { { '\@' => 'text' } });
    1;
};
ok($base_loaded, 'Moose superclass with a HashRef default compiles') or diag($@);

my $role_loaded = eval qq{
    package $role;
    use Moose::Role;
    has role_payload => (is => 'ro', isa => 'HashRef', default => sub { { role => [1, 2] } });
    1;
};
ok($role_loaded, 'dynamic Moose role with HashRef attribute compiles') or diag($@);
my $second_role_loaded = eval qq{
    package $second_role;
    use Moose::Role;
    has format_cache => (is => 'ro', isa => 'HashRef', default => sub { { cache => 1 } });
    1;
};
ok($second_role_loaded, 'second dynamic Moose role compiles') or diag($@);

my $meta;
my $class_loaded = eval {
    $meta = Moose::Meta::Class->create($package, superclasses => [$base]);
    $meta->add_attribute(payload => (is => 'ro', isa => 'HashRef', required => 1));
    Moose::Util::apply_all_roles($meta->name, $role, $second_role);
    $meta->make_immutable;
    1;
};
ok($class_loaded, 'dynamic class composes roles with HashRef attributes') or diag($@);

my $args = { payload => { key => [1, 2] }, _alt_epoch_year => 1904 };
my $class = $meta->name;
my $object;
eval '$object = $class->new(%$args)';
ok($object, 'dynamically evaluated constructor accepts the hash reference') or diag($@);
is_deeply($object->payload, { key => [1, 2] }, 'dynamic constructor preserves the hash reference value')
    if $object;
is($object->payload, $args->{payload}, 'dynamic constructor retains reference identity')
    if $object;
is_deeply($object->defined_excel_translations, { '@' => 'text' },
    'HashRef default survives dynamic class construction') if $object;
is_deeply($object->role_payload, { role => [1, 2] },
    'role HashRef default survives dynamic class construction') if $object;

done_testing;
