package Class::MOP::PurePerl;

# Pure-Perl replacement for the XS code in Moose-2.4000.
#
# Upstream Moose ships a single shared library that provides:
#   - Trivial accessor methods (INSTALL_SIMPLE_READER) on a handful of
#     mixin classes (Mixin::AttributeCore, Mixin::HasAttributes,
#     Mixin::HasMethods, Method, Method::Generated, Class, Package,
#     Instance).
#   - A pure-Perl-friendly `_method_map` on Mixin::HasMethods that
#     materialises a method object per CODE entry in the package stash.
#   - `Class::MOP::get_code_info($coderef)` -> ($pkg, $name).
#   - `Moose::Meta::Role::Application::ToInstance::_reset_amagic` to
#     re-flag overload after applying a role to a blessed object.
#   - Magic on the boolean export-flag scalar (Moose::Exporter), which
#     only affects warning shape when an over-eager `use Moose;` line
#     wasn't followed by `__PACKAGE__->meta->make_immutable`. Stubbed.
#
# This file installs all of the above in pure Perl. Loaded from
# `Class::MOP.pm` when running on PerlOnJava (or when the user sets
# MOOSE_PUREPERL=1 on real Perl).
#
# Dependencies: B for code-info, Scalar::Util::blessed, mro for
# package-cache-flag. All available on PerlOnJava.

use strict;
use warnings;

use B ();
use Scalar::Util qw(blessed reftype);

our $VERSION = '2.4000';

# ---------------------------------------------------------------------------
# Helper to install simple hash-slot readers.
sub _install_reader {
    my ($pkg, $method, $slot) = @_;
    $slot //= $method;
    no strict 'refs';
    *{"${pkg}::${method}"} = sub : method {
        $_[0]->{$slot};
    };
}

sub _install_readers {
    my ($pkg, @names) = @_;
    _install_reader($pkg, $_) for @names;
}

# ---------------------------------------------------------------------------
# Class::MOP::Mixin::AttributeCore
_install_readers('Class::MOP::Mixin::AttributeCore', qw(
    name
    accessor
    reader
    writer
    predicate
    clearer
    builder
    init_arg
    initializer
    definition_context
    insertion_order
));

# Class::MOP::Mixin::HasAttributes
_install_reader('Class::MOP::Mixin::HasAttributes', 'attribute_metaclass');
_install_reader('Class::MOP::Mixin::HasAttributes', '_attribute_map', 'attributes');

# Class::MOP::Mixin::HasMethods
_install_reader('Class::MOP::Mixin::HasMethods', 'method_metaclass');
_install_reader('Class::MOP::Mixin::HasMethods', 'wrapped_method_metaclass');

# Class::MOP::Method
_install_readers('Class::MOP::Method', qw(name package_name body));

# Class::MOP::Method::Generated
_install_readers('Class::MOP::Method::Generated', qw(is_inline definition_context));

# Class::MOP::Method::Inlined
_install_reader('Class::MOP::Method::Inlined', '_expected_method_class');

# Class::MOP::Class
_install_readers('Class::MOP::Class', qw(
    instance_metaclass
    immutable_trait
    constructor_class
    constructor_name
    destructor_class
));

# Class::MOP::Package
_install_reader('Class::MOP::Package', 'name', 'package');

# Class::MOP::Instance
_install_reader('Class::MOP::Instance', 'associated_metaclass');

# Class::MOP::Attribute
_install_readers('Class::MOP::Attribute', qw(associated_class associated_methods));

# ---------------------------------------------------------------------------
# Class::MOP::Mixin::HasMethods::_method_map
#
# Returns a hashref of method-name => Class::MOP::Method (or coderef),
# updated against the actual contents of the package stash. The XS
# does this with a stash walk; we use B / typeglobs.
{
    no strict 'refs';
    *{'Class::MOP::Mixin::HasMethods::_method_map'} = sub {
        my $self = $_[0];
        my $class_name = $self->{package};
        my $cache_flag = mro::get_pkg_gen($class_name);
        my $current_flag = $self->{package_cache_flag};
        my $map = $self->{methods} //= {};

        if (!defined $current_flag || $current_flag != $cache_flag) {
            # Walk the stash and prune entries whose body no longer
            # matches the package's current sub.
            my $stash = do {
                no strict 'refs';
                \%{"${class_name}::"};
            };
            for my $name (keys %$map) {
                my $method = $map->{$name};
                next unless ref $method;
                my $body = blessed($method) && $method->isa('Class::MOP::Method')
                    ? $method->body
                    : $method;
                my $glob = $stash->{$name};
                my $stash_code;
                if (defined $glob) {
                    if (ref \$glob eq 'GLOB') {
                        $stash_code = *{$glob}{CODE};
                    } elsif (ref $glob eq 'CODE') {
                        $stash_code = $glob;
                    }
                }
                if (!$stash_code || $stash_code != $body) {
                    delete $map->{$name};
                }
            }
            $self->{package_cache_flag} = $cache_flag;
        }
        return $map;
    };
}

# ---------------------------------------------------------------------------
# Class::MOP::get_code_info($coderef) -> ($package, $name)
{
    no strict 'refs';
    *{'Class::MOP::get_code_info'} = sub {
        my ($coderef) = @_;
        return unless ref $coderef eq 'CODE';
        my $cv = B::svref_2object($coderef);
        return unless $cv->isa('B::CV');
        my $gv = $cv->GV;
        return unless ref $gv && !$gv->isa('B::SPECIAL');
        my $pkg  = $gv->STASH->NAME;
        my $name = $gv->NAME;
        return ($pkg, $name);
    } unless defined &Class::MOP::get_code_info;
}

# ---------------------------------------------------------------------------
# Moose::Meta::Role::Application::ToInstance::_reset_amagic
# In real Moose this flips overload magic on existing references to
# the blessed object. PerlOnJava's overload tracking is per-package,
# so this is a no-op for us.
{
    no strict 'refs';
    *{'Moose::Meta::Role::Application::ToInstance::_reset_amagic'} = sub { };
}

# ---------------------------------------------------------------------------
# Moose::Util::TypeConstraints::Builtins / Moose::Exporter export-flag
# magic — stubs. Real impl uses MAGIC_set; we don't need it for
# correctness, only for warning shape.
{
    no strict 'refs';
    *{'Moose::Exporter::_make_unimport_hooks'} = sub { } unless defined &Moose::Exporter::_make_unimport_hooks;
}

1;
