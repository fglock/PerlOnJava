package UNIVERSAL;

# Stub for UNIVERSAL module - actual implementation is in Java (Universal.java)
# This file exists so %INC has a real file path that can be opened by tests

use strict;
use warnings;

our $VERSION = '1.17';

# The Java implementation (Universal.java) provides:
# - can($method) - check if object/class can perform method
# - isa($class) - check if object/class is or inherits from class
# - DOES($role) - check if object/class does a role (same as isa)
# - VERSION([$require]) - get/check package version

1;

__END__

=head1 NAME

UNIVERSAL - base class for ALL classes (blessed references)

=head1 SYNOPSIS

    $obj->isa('ClassName');
    $obj->can('method');
    $obj->DOES('RoleName');
    $obj->VERSION;

    ClassName->isa('ParentClass');
    ClassName->can('method');
    ClassName->VERSION($required);

=head1 DESCRIPTION

UNIVERSAL is the base class which all blessed references inherit from.
This is the PerlOnJava implementation.

=head1 METHODS

=over 4

=item $obj->isa(TYPE)

Returns true if $obj is blessed into package TYPE or inherits from TYPE.

=item $obj->can(METHOD)

Returns a reference to the method if $obj can perform METHOD, false otherwise.

=item $obj->DOES(ROLE)

Returns true if $obj does the role ROLE. In PerlOnJava, this is equivalent to isa().

=item $obj->VERSION([REQUIRE])

Returns the version of the package $obj is blessed into. If REQUIRE is given,
dies if the version is less than REQUIRE.

=back

=cut
