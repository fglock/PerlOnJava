package Moose;

# PerlOnJava Moose shim.
#
# This is NOT the real Moose. It is a thin compatibility layer that delegates
# to Moo, intended to make modules that use the simple Moose attribute /
# inheritance / role API work on PerlOnJava (which cannot run Moose's XS
# implementation).
#
# Supported (covers the long tail of CPAN modules that use Moose for plain
# attribute declarations):
#   - use Moose;
#   - has $name => (is => 'ro|rw', isa => 'Type', default => ..., builder => ...,
#                   required => ..., lazy => ..., trigger => ..., predicate => ...,
#                   clearer => ..., handles => ..., weak_ref => ..., init_arg => ...,
#                   coerce => ...)
#   - extends 'Parent::Class', ...
#   - with 'Role::Name', ...
#   - before / after / around method modifiers
#   - String type-constraint names: Any, Item, Defined, Undef, Bool, Value,
#     Ref, Str, Num, Int, ScalarRef, ArrayRef, HashRef, CodeRef, RegexpRef,
#     GlobRef, FileHandle, Object, ClassName. Unknown strings are treated as
#     class names (`isa`-checked).
#   - __PACKAGE__->meta->make_immutable (no-op)
#
# NOT supported:
#   - Full meta-object protocol introspection ($class->meta->get_all_attributes etc.)
#   - Moose::Util::TypeConstraints subtype/coerce/enum machinery beyond the
#     simple stubs in Moose::Util::TypeConstraints
#   - Moose::Exporter-based modules that drive deep MOP APIs
#   - Native traits (Array, Hash, Counter, ...)
#
# See dev/modules/moose_support.md for the broader plan.

use strict;
use warnings;

our $VERSION = '2.4000';  # Match the version most CPAN code expects.

# Prevent Moo::sification from triggering its Moose-bridge (which would
# require the real Class::MOP) when Moo loads. The bridge fires from
# Moo::sification->import() if $INC{"Moose.pm"} is already true — and in
# our case it always is, because *we* are Moose.pm. We must short-circuit
# this BEFORE Moo gets a chance to load, hence the BEGIN block.
BEGIN {
    local $@;
    eval {
        require Moo::sification;
        no warnings 'once';
        $Moo::sification::setup_done = 1;
        $Moo::sification::disabled   = 1;
        1;
    };
}

use Moo ();
use Carp ();
use Scalar::Util ();

# Make sure Class::MOP's helpers are defined BEFORE Moo's role-composition
# code runs. Moo's _Utils / Moo::Role call `Class::MOP::class_of` whenever
# `$INC{"Moose.pm"}` is set — and that is *always* set under this shim,
# because we are Moose.pm. Without this require, those calls die with
# "Undefined subroutine &Class::MOP::class_of called". The shim's
# Class::MOP returns "no metaclass" for everything, which is the correct
# answer here (we have no real Moose metaclasses to find).
use Class::MOP ();

# Pre-load Moose::Util::MetaRole so MooseX::* extensions that call
# Moose::Util::MetaRole::apply_metaroles(...) without a `use` line
# (relying on Moose to have loaded it) don't get an "Undefined subroutine"
# error. Under our shim it's a no-op.
use Moose::Util::MetaRole ();

# Pre-load all the Moose::Meta::* skeleton classes so tests that do
# `use Moose;` and then call e.g. Moose::Meta::Class->initialize(...) /
# Moose::Meta::Role->create_anon_role(...) / Moose::Meta::Attribute->new(...)
# find their methods. Without these requires, the package exists in
# @INC but isn't loaded, and callers get "Can't locate object method ...
# via package Moose::Meta::Class".
use Moose::Meta::Class ();
use Moose::Meta::Role ();
use Moose::Meta::Attribute ();
use Moose::Meta::Method ();
use Moose::Meta::Method::Delegation ();
use Moose::Meta::TypeConstraint ();
use Moose::Exporter ();
use Moose::Exception ();
use Moose::Util ();
use Moose::Util::TypeConstraints ();

# ---------------------------------------------------------------------------
# Type constraint name -> validator coderef. Returns a Moo-compatible
# isa-checker that croaks on validation failure.
# ---------------------------------------------------------------------------

my %TYPE_CHECKS = (
    Any        => sub { 1 },
    Item       => sub { 1 },
    Defined    => sub { defined $_[0] },
    Undef      => sub { !defined $_[0] },
    Bool       => sub { !defined $_[0] || $_[0] eq '' || $_[0] eq '0' || $_[0] eq '1' },
    Value      => sub { defined $_[0] && !ref $_[0] },
    Ref        => sub { ref $_[0] ? 1 : 0 },
    Str        => sub { defined $_[0] && !ref $_[0] },
    Num        => sub {
        defined $_[0] && !ref $_[0]
            && $_[0] =~ /\A-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?\z/;
    },
    Int        => sub { defined $_[0] && !ref $_[0] && $_[0] =~ /\A-?\d+\z/ },
    ScalarRef  => sub { ref $_[0] eq 'SCALAR' || ref $_[0] eq 'REF' },
    ArrayRef   => sub { ref $_[0] eq 'ARRAY' },
    HashRef    => sub { ref $_[0] eq 'HASH' },
    CodeRef    => sub { ref $_[0] eq 'CODE' },
    RegexpRef  => sub { ref $_[0] eq 'Regexp' },
    GlobRef    => sub { ref $_[0] eq 'GLOB' },
    FileHandle => sub {
        ref $_[0] eq 'GLOB'
            || (Scalar::Util::blessed($_[0]) && $_[0]->isa('IO::Handle'));
    },
    Object     => sub { Scalar::Util::blessed($_[0]) ? 1 : 0 },
    ClassName  => sub {
        defined $_[0] && !ref $_[0] && $_[0] =~ /\A[A-Za-z_][\w:]*\z/;
    },
);

sub _make_isa_check {
    my ($type) = @_;

    # Already a coderef or Type::Tiny-like object: pass through.
    return $type if ref $type eq 'CODE';
    return $type if Scalar::Util::blessed($type) && $type->can('check');

    # Strip "Maybe[Foo]" -> Foo with maybe-undef wrapper.
    if ($type =~ /\AMaybe\[(.+)\]\z/) {
        my $inner = _make_isa_check($1);
        return sub {
            return if !defined $_[0];
            $inner->(@_);
        };
    }

    # Strip "ArrayRef[Foo]" / "HashRef[Foo]" - drop parameterization for now.
    if ($type =~ /\A(ArrayRef|HashRef)\[/) {
        my $base = $1;
        my $check = $TYPE_CHECKS{$base};
        my $name  = $type;
        return sub {
            $check->($_[0])
                or Carp::croak("Validation for '$name' failed for value "
                    . (defined $_[0] ? "'$_[0]'" : 'undef'));
        };
    }

    if (my $check = $TYPE_CHECKS{$type}) {
        my $name = $type;
        return sub {
            $check->($_[0])
                or Carp::croak("Validation for '$name' failed for value "
                    . (defined $_[0] ? "'$_[0]'" : 'undef'));
        };
    }

    # Treat unknown name as a class name; verify via UNIVERSAL::isa.
    my $class = $type;
    return sub {
        my $val = $_[0];
        Scalar::Util::blessed($val) && $val->isa($class)
            or Carp::croak("Validation for class '$class' failed for value "
                . (defined $val ? "'$val'" : 'undef'));
    };
}

# ---------------------------------------------------------------------------
# Translate a Moose-style has() call into a Moo-compatible one. Drops
# Moose-only options Moo doesn't recognize (with a soft warning the first
# time per option).
# ---------------------------------------------------------------------------

my %MOO_KNOWN_OPTS = map { $_ => 1 } qw(
    is isa coerce default builder lazy required init_arg
    predicate clearer handles trigger weak_ref reader writer
    moosify
);

sub _translate_has_args {
    my ($name_or_names, %opts) = @_;

    if (exists $opts{isa} && !ref $opts{isa}) {
        $opts{isa} = _make_isa_check($opts{isa});
    }
    elsif (exists $opts{isa}
        && Scalar::Util::blessed($opts{isa})
        && $opts{isa}->can('check'))
    {
        # Type::Tiny-style. Convert to coderef Moo accepts.
        my $tt = $opts{isa};
        $opts{isa} = sub { $tt->assert_valid($_[0]) };
    }

    # lazy_build => 1 expands to lazy + builder + clearer + predicate (Moose
    # convention). Translate into the underlying primitives.
    if (delete $opts{lazy_build}) {
        $opts{lazy} = 1;
        my $base = ref $name_or_names ? $name_or_names->[0] : $name_or_names;
        $opts{builder}   //= "_build_$base";
        $opts{clearer}   //= "clear_$base";
        $opts{predicate} //= "has_$base";
    }

    # 'auto_deref' is a Moose-ism for ArrayRef/HashRef accessors.
    delete $opts{auto_deref};

    # Documentation/traits/metaclass/order are MOP-only metadata.
    delete @opts{qw(documentation traits metaclass order definition_context)};

    return ($name_or_names, %opts);
}

# ---------------------------------------------------------------------------
# import / unimport
# ---------------------------------------------------------------------------

sub import {
    my ($class, @args) = @_;
    my $target = caller;

    return if $target eq 'main';  # `perl -MMoose -e ...` shouldn't blow up.

    strict->import;
    warnings->import;

    # Run `use Moo` inside $target so Moo's caller() detection sees it.
    my $err;
    {
        local $@;
        eval "package $target; use Moo; 1" or $err = $@ || 'unknown error';
    }
    Carp::croak("Moose shim: failed to load Moo for $target: $err") if $err;

    # Wrap the target's `has` to translate Moose-style options before Moo
    # sees them, AND record the attribute on the target's _FakeMeta so
    # $meta->get_attribute_list / find_attribute_by_name work.
    my $orig_has = do { no strict 'refs'; \&{"${target}::has"} };
    if ($orig_has) {
        no strict 'refs';
        no warnings 'redefine';
        *{"${target}::has"} = sub {
            my @orig_args = @_;
            my $rv = $orig_has->( _translate_has_args(@orig_args) );
            # Track on metaclass.
            my $meta = Moose::_FakeMeta->_for($target);
            my $names = $orig_args[0];
            for my $n (ref $names eq 'ARRAY' ? @$names : ($names)) {
                next unless defined $n && !ref $n;
                my %opts = @orig_args[1..$#orig_args];
                $meta->add_attribute(name => $n, %opts);
            }
            return $rv;
        };
    }

    # Provide Moose::Object as an inheritance marker so $obj->isa('Moose::Object')
    # is true, matching common idioms.
    {
        no strict 'refs';
        my @isa = @{"${target}::ISA"};
        unless (grep { $_ eq 'Moose::Object' } @isa) {
            push @{"${target}::ISA"}, 'Moose::Object';
        }
    }

    # Install a meta() stub for $class->meta->make_immutable() etc.
    no strict 'refs';
    unless (defined &{"${target}::meta"}) {
        *{"${target}::meta"} = sub { Moose::_FakeMeta->_for($target) };
    }
}

sub unimport {
    my $target = caller;
    no strict 'refs';
    for my $sym (qw(has extends with before after around requires meta)) {
        delete ${"${target}::"}{$sym};
    }
}

# ---------------------------------------------------------------------------
# Stub metaclass so `__PACKAGE__->meta->make_immutable` and a few common
# idioms don't blow up.
# ---------------------------------------------------------------------------

package Moose::_FakeMeta;

# Stub metaclass returned by $class->meta and Class::MOP::class_of-via-
# the-shim. It is not a real Class::MOP::Class, but it inherits from
# Class::MOP::Class and Moose::Meta::Class so that
#   isa_ok($meta, 'Class::MOP::Class')
#   isa_ok($meta, 'Moose::Meta::Class')
# pass.
#
# Method coverage is the bare minimum the upstream Moose 2.4000 test
# suite reaches for. Everything is implemented as a "remember what we
# saw" registry — no real meta-object protocol. See dev/modules/moose_support.md.

require Class::MOP::Class;
require Moose::Meta::Class;
our @ISA = ('Moose::Meta::Class', 'Class::MOP::Class');

# Per-class cache so that $class->meta returns the same metaclass each
# call. Required for tests that compare metaclass identity.
my %META_CACHE;

sub _for {
    my ($class, $for) = @_;
    return $META_CACHE{$for} ||= bless {
        name        => $for,
        attributes  => {},  # name => Class::MOP::Attribute-ish
        attr_order  => [],  # insertion order
        is_immutable => 0,
        roles       => [],
    }, $class;
}

sub name           { $_[0]->{name} }
sub make_immutable { $_[0]->{is_immutable} = 1; $_[0] }
sub make_mutable   { $_[0]->{is_immutable} = 0; $_[0] }
sub is_immutable   { $_[0]->{is_immutable} ? 1 : 0 }
sub is_mutable     { $_[0]->{is_immutable} ? 0 : 1 }
sub is_anon_class  { 0 }
sub meta           { Moose::_FakeMeta->_for(ref $_[0] || $_[0]) }

# ---------------------------------------------------------------------------
# Attribute tracking. Moose.pm's `has` wrapper calls
# $meta->add_attribute(name => $name, %opts) so $meta->get_attribute_list
# and friends work like upstream.
# ---------------------------------------------------------------------------

sub add_attribute {
    my $self = shift;
    require Class::MOP::Attribute;
    my $attr;
    if (@_ == 1 && ref $_[0]) {
        # Already an attribute object.
        $attr = $_[0];
    }
    else {
        $attr = Class::MOP::Attribute->new(@_);
    }
    my $name = $attr->name;
    return unless defined $name;
    unless (exists $self->{attributes}{$name}) {
        push @{ $self->{attr_order} }, $name;
    }
    $self->{attributes}{$name} = $attr;
    return $attr;
}

sub get_attribute {
    my ($self, $name) = @_;
    return unless defined $name;
    return $self->{attributes}{$name};
}

sub find_attribute_by_name {
    my ($self, $name) = @_;
    return unless defined $name;
    return $self->{attributes}{$name} if $self->{attributes}{$name};
    # Walk @ISA to find the attribute on a parent class.
    require mro;
    for my $parent (@{ mro::get_linear_isa($self->{name}) }) {
        next if $parent eq $self->{name};
        my $pmeta = $META_CACHE{$parent} or next;
        my $a = $pmeta->{attributes}{$name};
        return $a if $a;
    }
    return;
}

sub has_attribute {
    my ($self, $name) = @_;
    return defined $self->find_attribute_by_name($name) ? 1 : 0;
}

sub remove_attribute {
    my ($self, $name) = @_;
    return unless defined $name && exists $self->{attributes}{$name};
    @{ $self->{attr_order} } = grep { $_ ne $name } @{ $self->{attr_order} };
    return delete $self->{attributes}{$name};
}

sub get_attribute_list {
    my $self = shift;
    return @{ $self->{attr_order} || [] };
}

sub get_all_attributes {
    my $self = shift;
    my @attrs;
    my %seen;
    require mro;
    for my $class (@{ mro::get_linear_isa($self->{name}) }) {
        my $m = $META_CACHE{$class} or next;
        for my $name (@{ $m->{attr_order} || [] }) {
            next if $seen{$name}++;
            push @attrs, $m->{attributes}{$name};
        }
    }
    return @attrs;
}

sub get_attribute_map { +{ %{ $_[0]->{attributes} || {} } } }

# ---------------------------------------------------------------------------
# Method introspection. We don't track methods explicitly; we read the
# package's stash on demand. Good enough for upstream tests that ask
# things like "does this class have method X?".
# ---------------------------------------------------------------------------

sub get_method {
    my ($self, $name) = @_;
    return unless defined $name;
    my $class = $self->{name};
    no strict 'refs';
    my $code = *{"${class}::${name}"}{CODE};
    return unless $code;
    require Class::MOP::Method;
    return Class::MOP::Method->wrap(
        body         => $code,
        name         => $name,
        package_name => $class,
    );
}

sub has_method {
    my ($self, $name) = @_;
    return 0 unless defined $name;
    my $class = $self->{name};
    no strict 'refs';
    return *{"${class}::${name}"}{CODE} ? 1 : 0;
}

sub get_method_list {
    my $self = shift;
    my $class = $self->{name};
    no strict 'refs';
    my $stash = \%{"${class}::"};
    my @methods;
    for my $sym (keys %$stash) {
        next if $sym =~ /::\z/;
        my $glob = $stash->{$sym};
        next unless ref \$glob eq 'GLOB' || (defined $glob);
        no strict 'refs';
        next unless *{"${class}::${sym}"}{CODE};
        push @methods, $sym;
    }
    return @methods;
}

# ---------------------------------------------------------------------------
# Object construction. Tests reach for $meta->new_object(%args) as an
# alternative to $class->new(%args). Forward to the class's new().
# ---------------------------------------------------------------------------

sub new_object {
    my ($self, @args) = @_;
    my $class = $self->{name};
    return $class->new(@args);
}

sub create_anon_class { Class::MOP::Class::create_anon_class('Class::MOP::Class', @_[1..$#_]) }

# ---------------------------------------------------------------------------
# Inheritance / role membership.
# ---------------------------------------------------------------------------

sub superclasses {
    my $self = shift;
    no strict 'refs';
    if (@_) { @{"$self->{name}::ISA"} = @_ }
    return @{"$self->{name}::ISA"};
}

sub linearized_isa {
    my $self = shift;
    require mro;
    @{ mro::get_linear_isa($self->{name}) };
}

sub class_precedence_list { goto &linearized_isa }

sub roles {
    my $self = shift;
    return @{ $self->{roles} || [] };
}

sub does_role {
    my ($self, $role) = @_;
    return 0 unless defined $role;
    my $class = $self->{name};
    return 1 if $class->can('DOES') && eval { $class->DOES($role) };
    if (defined &Role::Tiny::does_role) {
        return 1 if Role::Tiny::does_role($class, $role);
    }
    return 0;
}

# Misc upstream APIs that some tests poke at.
sub identifier              { $_[0]->{name} }
sub version                 { no strict 'refs'; ${"$_[0]->{name}::VERSION"} }
sub authority               { undef }
sub _attach_to_class        { $_[0] }
sub _attach_to_metaclass    { $_[0] }
sub add_method              { 1 }   # methods are already installed by Moo
sub remove_method           { 1 }
sub add_role                { push @{ $_[0]->{roles} ||= [] }, $_[1]; $_[0] }
sub _add_meta_method        { 1 }
sub add_method_modifier     { 1 }   # Moo's `before`/`after`/`around` already installed

# Aliases / minor extras.
sub find_method_by_name     { goto &get_method }
sub get_method_map          { my $self = shift; +{ map { $_ => $self->get_method($_) } $self->get_method_list } }
sub attribute_metaclass     { 'Moose::Meta::Attribute' }
sub method_metaclass        { 'Moose::Meta::Method' }
sub instance_metaclass      { 'Class::MOP::Instance' }
sub constructor_class       { 'Moose::Meta::Method::Constructor' }
sub destructor_class        { 'Moose::Meta::Method::Destructor' }
sub rebless_instance        {
    my ($self, $instance, %args) = @_;
    bless $instance, $self->{name};
    $instance->$_($args{$_}) for grep { $instance->can($_) } keys %args;
    return $instance;
}
sub rebless_instance_back   { my ($self, $instance) = @_; bless $instance, $self->{name}; $instance }
sub get_package_symbol      {
    my ($self, $name) = @_;
    no strict 'refs';
    return *{"$self->{name}::$name"};
}
sub list_all_package_symbols {
    my ($self, $type) = @_;
    no strict 'refs';
    return grep { !/::\z/ } keys %{"$self->{name}::"};
}
sub is_pristine             { 0 }   # Moose-using classes are by definition not pristine
sub _is_compatible_with     { 1 }
sub _check_metaclass_compatibility { 1 }
sub immutable_options       { () }

# Method modifiers — Moo's `before`/`after`/`around` already installed
# the wrappers; these are the metaclass hooks tests poke at.
sub add_before_method_modifier { my ($self, $name, $code) = @_;
    require Class::Method::Modifiers;
    Class::Method::Modifiers::install_modifier($self->{name}, 'before', $name, $code);
}
sub add_after_method_modifier  { my ($self, $name, $code) = @_;
    require Class::Method::Modifiers;
    Class::Method::Modifiers::install_modifier($self->{name}, 'after',  $name, $code);
}
sub add_around_method_modifier { my ($self, $name, $code) = @_;
    require Class::Method::Modifiers;
    Class::Method::Modifiers::install_modifier($self->{name}, 'around', $name, $code);
}
sub add_override_method_modifier { add_around_method_modifier(@_) }
sub add_augment_method_modifier  { add_around_method_modifier(@_) }

1;

__END__

=head1 NAME

Moose - PerlOnJava compatibility shim that delegates to Moo

=head1 SYNOPSIS

    package MyClass;
    use Moose;

    has name => (is => 'rw', isa => 'Str', default => 'world');
    has age  => (is => 'ro', isa => 'Int', required => 1);

    sub greet { "Hello, " . $_[0]->name }

    no Moose;
    __PACKAGE__->meta->make_immutable;

=head1 DESCRIPTION

This is not the real CPAN Moose distribution. PerlOnJava cannot install
Moose because Moose ships substantial XS code (13 .xs files plus mop.c).
This shim provides a useful subset of the Moose API by translating
declarations into the equivalent Moo idioms.

If you need the full Moose meta-object protocol, run on system Perl with the
real Moose installed. See C<dev/modules/moose_support.md> for the longer-term
plan to bundle a pure-Perl Class::MOP and Moose port.

=head1 SEE ALSO

L<Moo>, L<Moose>, C<dev/modules/moose_support.md>

=cut
