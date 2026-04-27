package metaclass;

# PerlOnJava `metaclass` pragma stub.
#
# Upstream metaclass installs a Class::MOP::Class for the calling package
# and gives it a `meta` method. PerlOnJava cannot host real Class::MOP::Class
# instances (yet), but the import is treated as a no-op: callers that try
# `use metaclass;` or `use metaclass 'My::Meta';` keep compiling, and they
# get a `meta` method that returns the same Moose::_FakeMeta stub the Moose
# shim already installs in `use Moose;`.
#
# See dev/modules/moose_support.md.

use strict;
use warnings;

our $VERSION = '2.4000';

sub import {
    my ($pragma, @args) = @_;

    my $target = caller;

    # Drop alternate metaclass / meta_name / *_metaclass / *_class options.
    # Under the shim they're all advisory.
    @args = () if @args;

    no strict 'refs';
    unless (defined &{"${target}::meta"}) {
        my $name = $target;
        *{"${target}::meta"} = sub {
            require Moose;
            Moose::_FakeMeta->_for($name);
        };
    }

    return;
}

1;

__END__

=head1 NAME

metaclass - PerlOnJava stub for the C<metaclass> pragma.

=head1 DESCRIPTION

Upstream this pragma wires a class up with a custom L<Class::MOP::Class>.
Under the PerlOnJava Moose-as-Moo shim there is no real metaclass, so this
module just installs a C<meta> method on the caller (returning the same
fake metaclass our Moose shim installs).

=cut
