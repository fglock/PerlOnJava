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
    # sees them.
    my $orig_has = do { no strict 'refs'; \&{"${target}::has"} };
    if ($orig_has) {
        no strict 'refs';
        no warnings 'redefine';
        *{"${target}::has"} = sub {
            $orig_has->( _translate_has_args(@_) );
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

sub _for {
    my ($class, $for) = @_;
    bless { name => $for }, $class;
}

sub name           { $_[0]->{name} }
sub make_immutable { $_[0] }
sub make_mutable   { $_[0] }
sub is_immutable   { 0 }
sub get_attribute_list { () }
sub get_all_attributes { () }
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
