use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require Moose; require Moose::Role; 1 }
        or plan skip_all => 'Moose required';
}

plan tests => 2;

{
    package MooseRoleConstantMethod::Provider;
    use Moose::Role;
    use constant PROVIDED => 'yes';
}

{
    package MooseRoleConstantMethod::Requirement;
    use Moose::Role;
    requires 'PROVIDED';
}

{
    package MooseRoleConstantMethod::Consumer;
    use Moose;
    with qw(
        MooseRoleConstantMethod::Requirement
        MooseRoleConstantMethod::Provider
    );
}

ok(
    MooseRoleConstantMethod::Consumer->can('PROVIDED'),
    'Moose discovers a constant supplied by a composed role',
);
is(
    MooseRoleConstantMethod::Consumer->PROVIDED,
    'yes',
    'composed constant role method remains callable',
);
