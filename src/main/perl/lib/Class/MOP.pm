package Class::MOP;

# PerlOnJava minimal Class::MOP stub.
#
# This is NOT the real Class::MOP. PerlOnJava cannot run Moose's XS
# meta-object protocol, and a full pure-Perl port is a separate, much
# larger effort (Phase D in dev/modules/moose_support.md).
#
# What this stub provides is just enough surface area for Moo's "is
# Moose loaded?" probes to answer truthfully ("no metaclass for that
# class") instead of dying with "Undefined subroutine" the moment our
# Moose shim sets $INC{"Moose.pm"}. That single change unblocks dozens
# of Moo-delegating Moose tests at compile time.
#
# Functions:
#   - class_of($name_or_obj)              -> undef (no Moose metaclass)
#   - get_metaclass_by_name($name)        -> undef
#   - store_metaclass_by_name($name, $m)  -> no-op (returns $m)
#   - remove_metaclass_by_name($name)     -> no-op
#   - get_all_metaclasses()               -> ()
#   - get_all_metaclass_names()           -> ()
#   - get_all_metaclass_instances()       -> ()
#   - get_code_info($cv)                  -> ($pkg, $name) via B
#   - is_class_loaded($name)              -> bool, mirrors Class::Load logic
#
# See dev/modules/moose_support.md for the broader plan.

use strict;
use warnings;

our $VERSION = '2.2207';  # Match a recent upstream version.

use Scalar::Util ();

# Pre-load the submodules so `use Class::MOP;` is enough to call
# Class::MOP::Class->initialize, Class::MOP::Attribute->new, etc.
# Upstream Moose's Class::MOP.pm pulls these in via XSLoader::load
# (which boot-loads Class::MOP::Mixin::*, Class::MOP::Method::*,
# Class::MOP::Instance, Class::MOP::Package, ...). Without these
# requires, tests that say `use Class::MOP;` and then call
# `Class::MOP::Class->initialize(...)` get "Can't locate object method
# initialize via package Class::MOP::Class".
require Class::MOP::Class;
require Class::MOP::Attribute;
require Class::MOP::Method;
require Class::MOP::Method::Accessor;
require Class::MOP::Instance;
require Class::MOP::Package;

# ---------------------------------------------------------------------------
# Metaclass registry. Stays empty under the shim — we never construct real
# Class::MOP::Class instances — but accept stores so consumers that try to
# register a metaclass don't blow up.
# ---------------------------------------------------------------------------

my %METAS;

sub class_of {
    my $thing = shift;
    return undef unless defined $thing;
    my $name = ref($thing) ? Scalar::Util::blessed($thing) : $thing;
    return undef unless defined $name;
    return $METAS{$name};
}

sub get_metaclass_by_name {
    my ($name) = @_;
    return undef unless defined $name;
    return $METAS{$name};
}

sub store_metaclass_by_name {
    my ($name, $meta) = @_;
    return unless defined $name;
    $METAS{$name} = $meta;
    return $meta;
}

sub remove_metaclass_by_name {
    my ($name) = @_;
    return unless defined $name;
    delete $METAS{$name};
    return;
}

sub does_metaclass_exist {
    my ($name) = @_;
    return defined $name && exists $METAS{$name};
}

sub get_all_metaclasses          { %METAS }
sub get_all_metaclass_names      { keys %METAS }
sub get_all_metaclass_instances  { values %METAS }

# ---------------------------------------------------------------------------
# get_code_info($cv) — used by Moose, Sub::Identify, and some role-composition
# code to ask "where did this coderef come from?". Answered via B, which on
# PerlOnJava reads packageName/subName off RuntimeCode (see Phase 1).
# ---------------------------------------------------------------------------

sub get_code_info {
    my ($cv) = @_;
    return unless ref($cv) eq 'CODE';

    require B;
    my $cvobj = B::svref_2object($cv);
    return unless $cvobj;

    my $gv = eval { $cvobj->GV };
    return unless $gv && ref $gv;

    my $stash = eval { $gv->STASH->NAME };
    my $name  = eval { $gv->NAME };
    return unless defined $stash && defined $name;

    return ($stash, $name);
}

# ---------------------------------------------------------------------------
# is_class_loaded — borrowed from Class::Load's logic. Some Moose code asks
# this directly via Class::MOP. We answer based on the package's symbol
# table rather than dragging in Class::Load.
# ---------------------------------------------------------------------------

sub is_class_loaded {
    my ($class) = @_;
    return 0 unless defined $class && length $class;
    return 0 if $class =~ /(?:\A|::)\z/;
    return 0 unless $class =~ /\A[A-Za-z_][\w:]*\z/;

    no strict 'refs';
    my $stash = \%{"${class}::"};
    return 0 unless %$stash;

    # A package is "loaded" if it has $VERSION, @ISA, or any subroutine.
    return 1 if defined ${"${class}::VERSION"};
    return 1 if @{"${class}::ISA"};
    for my $sym (keys %$stash) {
        next if $sym =~ /::\z/;
        my $glob = $stash->{$sym};
        next unless ref \$glob eq 'GLOB';
        return 1 if defined *{$glob}{CODE};
    }
    return 0;
}

# ---------------------------------------------------------------------------
# load_class / load_first_existing_class — minimal pass-throughs to require.
# Some Moose code reaches for these directly.
# ---------------------------------------------------------------------------

sub load_class {
    my ($class) = @_;
    return 1 if is_class_loaded($class);
    (my $file = "$class.pm") =~ s{::}{/}g;
    require $file;
    return 1;
}

sub load_first_existing_class {
    my @classes = @_;
    for my $class (@classes) {
        my $ok = eval { load_class($class); 1 };
        return $class if $ok;
    }
    require Carp;
    Carp::croak("Can't locate any of: @classes");
}

1;

__END__

=head1 NAME

Class::MOP - PerlOnJava minimal shim (no real meta-object protocol)

=head1 DESCRIPTION

PerlOnJava ships a small subset of the Class::MOP API to keep Moo's
"is Moose loaded?" probes happy when our Moose shim sets
C<$INC{"Moose.pm"}>. The functions here intentionally answer "no
metaclass" for every class, because under the shim Moose classes are
really Moo classes with no MOP introspection layer.

For the full meta-object protocol, run on system Perl with the real
Moose / Class::MOP installed. See C<dev/modules/moose_support.md> for
the longer-term plan.

=head1 SEE ALSO

L<Moose>, L<Class::MOP>, L<Moo>

=cut
