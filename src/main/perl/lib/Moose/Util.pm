package Moose::Util;

# PerlOnJava Moose::Util shim.
#
# Upstream Moose::Util is a 700-line grab-bag that drives the meta-object
# protocol (role application, alias resolution, Moose::Exception throwing,
# attribute mining, ...). PerlOnJava can't host the real MOP, but a
# shim that covers the few helpers tests reach for keeps a lot of files
# compiling and lets simple assertions actually run.
#
# Provides: find_meta, is_role, does_role, throw_exception (re-die-style),
# english_list, get_all_attribute_values, get_all_init_args.
#
# See dev/modules/moose_support.md.

use strict;
use warnings;

our $VERSION = '2.4000';

use Carp ();
use Scalar::Util qw(blessed);
use Class::MOP ();

use Exporter 'import';

our @EXPORT_OK = qw(
    find_meta
    is_role
    does_role
    search_class_by_role
    ensure_all_roles
    apply_all_roles
    with_traits
    get_all_init_args
    get_all_attribute_values
    resolve_metatrait_alias
    resolve_metaclass_alias
    add_method_modifier
    english_list
    meta_attribute_alias
    meta_class_alias
    throw_exception
);

our %EXPORT_TAGS = ( all => [@EXPORT_OK] );

# ---------------------------------------------------------------------------
# Metaclass lookup. Under the Moose-as-Moo shim, every Moose-using class has
# a ->meta() method (installed by Moose.pm / Moose::Role) that returns a
# Moose::_FakeMeta. Class::MOP::class_of in this distribution returns undef
# by design (so Moo's own internal Moose-is-loaded probes go down the
# correct branch); for the user-facing `find_meta` we want a useful answer.
# ---------------------------------------------------------------------------

sub find_meta {
    my $thing = shift;
    return undef unless defined $thing;
    my $name = blessed($thing) || $thing;
    return undef unless defined $name && length $name && !ref $name;
    return undef unless $name->can('meta');
    my $meta = eval { $name->meta };
    return $meta;
}

sub is_role {
    my ($thing) = @_;
    my $name = blessed($thing) || $thing;
    return 0 unless defined $name && !ref $name;
    return 0 unless Class::MOP::is_class_loaded($name);
    # Moo::Role marks roles via Role::Tiny's registry; check there.
    no strict 'refs';
    return 1 if defined &{"Role::Tiny::Role"} && Role::Tiny->is_role($name);
    return 0;
}

sub does_role {
    my ($thing, $role) = @_;
    return 0 unless defined $thing && defined $role;
    my $name = blessed($thing) || $thing;
    return 0 unless defined $name && !ref $name && length $name;
    return 0 unless $name->can('DOES') || $name->can('isa');
    return 1 if eval { $name->DOES($role) };
    # Fallback: Role::Tiny tracks role consumption.
    if (defined &Role::Tiny::does_role) {
        return 1 if Role::Tiny::does_role($name, $role);
    }
    return 0;
}

sub search_class_by_role {
    my ($thing, $role) = @_;
    my $name = blessed($thing) || $thing;
    return undef unless defined $name;
    no strict 'refs';
    require mro;
    for my $class (@{ mro::get_linear_isa($name) }) {
        return $class if does_role($class, $role);
    }
    return undef;
}

sub ensure_all_roles {
    my ($applicant, @roles) = @_;
    apply_all_roles($applicant, grep { !does_role($applicant, $_) } @roles);
    return;
}

sub apply_all_roles {
    my ($applicant, @args) = @_;
    return unless @args;
    my $name = blessed($applicant) || $applicant;
    my $err;
    {
        local $@;
        # Filter out option hashrefs (Moose's apply_all_roles supports
        # `Role => { -alias => {...} }`); keep only the role names.
        my @roles = grep { !ref } @args;
        my $code = "package $name; require Moo::Role; Moo::Role->apply_roles_to_package('$name', "
                 . join(',', map { "'$_'" } @roles) . "); 1";
        eval $code or $err = $@ || 'unknown error';
    }
    Carp::croak($err) if $err;
    return;
}

sub with_traits {
    my ($base, @traits) = @_;
    return $base unless @traits;
    apply_all_roles($base, @traits);
    return $base;
}

# ---------------------------------------------------------------------------
# Attribute introspection. Moo doesn't expose this directly; we walk
# %Moo::MAKERS if present, otherwise return empty.
# ---------------------------------------------------------------------------

sub get_all_attribute_values {
    my ($meta, $obj) = @_;
    return {} unless ref $obj;
    return {} unless $meta && $meta->can('get_all_attributes');
    my %vals;
    for my $attr ($meta->get_all_attributes) {
        my $name = ref $attr ? $attr->name : $attr;
        next unless defined $name;
        $vals{$name} = $obj->{$name} if exists $obj->{$name};
    }
    return \%vals;
}

sub get_all_init_args {
    my ($meta, $obj) = @_;
    my $vals = get_all_attribute_values($meta, $obj);
    return $vals;  # under the shim, init_arg == attribute name
}

# ---------------------------------------------------------------------------
# Trait/metaclass alias resolution — the shim has no metaclass system, so
# return the input unchanged.
# ---------------------------------------------------------------------------

sub resolve_metatrait_alias  { $_[1] }
sub resolve_metaclass_alias  { $_[1] }
sub meta_attribute_alias     { return; }
sub meta_class_alias         { return; }

# ---------------------------------------------------------------------------
# add_method_modifier — under the shim, before/after/around are installed
# by Moo via `use Moo`'s import. This helper is uncommon in user code; we
# implement the trivial case (single coderef modifier on a single class).
# ---------------------------------------------------------------------------

sub add_method_modifier {
    my ($class, $type, $args) = @_;
    return unless ref $args eq 'ARRAY' && @$args >= 2;
    my $code = pop @$args;
    require Class::Method::Modifiers;
    my $installer = "Class::Method::Modifiers::install_modifier";
    no strict 'refs';
    return unless defined &$installer;
    return $installer->($class, $type, @$args, $code);
}

# ---------------------------------------------------------------------------
# Pretty-print helpers — used by Moose::Exception and a handful of test
# diagnostics.
# ---------------------------------------------------------------------------

sub english_list {
    my @items = @_;
    return ''        if !@items;
    return $items[0] if @items == 1;
    return "$items[0] and $items[1]" if @items == 2;
    my $last = pop @items;
    return join(', ', @items) . ", and $last";
}

# ---------------------------------------------------------------------------
# throw_exception($name, %args) — upstream loads
# Moose::Exception::$name and dies with an instance. Under the shim we
# don't ship the exception classes, so we die with a plain message that
# at least surfaces the exception name and args.
# ---------------------------------------------------------------------------

sub throw_exception {
    my ($exception_name, %args) = @_;
    my $msg = defined $args{message} ? $args{message} : $exception_name;
    Carp::croak($msg);
}

1;

__END__

=head1 NAME

Moose::Util - PerlOnJava shim covering the most-used Moose::Util helpers.

=head1 DESCRIPTION

This is a small subset of L<Moose::Util> implemented on top of the
Moose-as-Moo shim. It covers the helpers that tests and downstream
modules tend to import directly: C<find_meta>, C<does_role>, C<is_role>,
C<apply_all_roles>, C<english_list>, C<throw_exception>, plus a few
trait/metaclass alias passes-through.

=cut
