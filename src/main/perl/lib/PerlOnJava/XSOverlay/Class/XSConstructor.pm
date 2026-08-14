package Class::XSConstructor;

# Pure-Perl implementations of the symbols normally installed by
# Class::XSConstructor's XS bootstrap.  XSLoader evaluates this file as an
# overlay when the CPAN distribution is earlier in @INC, so the distribution's
# own metadata-building Perl code remains authoritative.

use strict;
use warnings;

sub XSCON_FLAG_REQUIRED              () { 1 }
sub XSCON_FLAG_HAS_TYPE_CONSTRAINT   () { 2 }
sub XSCON_FLAG_HAS_TYPE_COERCION     () { 4 }
sub XSCON_FLAG_HAS_DEFAULT           () { 8 }
sub XSCON_FLAG_NO_INIT_ARG           () { 16 }
sub XSCON_FLAG_HAS_INIT_ARG          () { 32 }
sub XSCON_FLAG_HAS_TRIGGER           () { 64 }
sub XSCON_FLAG_WEAKEN                () { 128 }
sub XSCON_FLAG_HAS_ALIASES           () { 256 }
sub XSCON_FLAG_HAS_SLOT_INITIALIZER  () { 512 }
sub XSCON_FLAG_UNDEF_TOLERANT        () { 1024 }
sub XSCON_FLAG_CLONE_ON_WRITE        () { 2048 }
sub XSCON_BITSHIFT_DEFAULTS          () { 16 }
sub XSCON_BITSHIFT_TYPES             () { 24 }

sub XSCON_DEFAULT_UNDEF       () { 1 }
sub XSCON_DEFAULT_ZERO        () { 2 }
sub XSCON_DEFAULT_ONE         () { 3 }
sub XSCON_DEFAULT_FALSE       () { 4 }
sub XSCON_DEFAULT_TRUE        () { 5 }
sub XSCON_DEFAULT_EMPTY_STR   () { 6 }
sub XSCON_DEFAULT_EMPTY_ARRAY () { 7 }
sub XSCON_DEFAULT_EMPTY_HASH  () { 8 }

sub XSCON_TYPE_BASE_ANY       () { 0 }
sub XSCON_TYPE_BASE_DEFINED   () { 1 }
sub XSCON_TYPE_BASE_REF       () { 2 }
sub XSCON_TYPE_BASE_BOOL      () { 3 }
sub XSCON_TYPE_BASE_INT       () { 4 }
sub XSCON_TYPE_BASE_PZINT     () { 5 }
sub XSCON_TYPE_BASE_NUM       () { 6 }
sub XSCON_TYPE_BASE_PZNUM     () { 7 }
sub XSCON_TYPE_BASE_STR       () { 8 }
sub XSCON_TYPE_BASE_NESTR     () { 9 }
sub XSCON_TYPE_BASE_CLASSNAME () { 10 }
sub XSCON_TYPE_BASE_OBJECT    () { 12 }
sub XSCON_TYPE_BASE_SCALARREF () { 13 }
sub XSCON_TYPE_BASE_CODEREF   () { 14 }
sub XSCON_TYPE_OTHER          () { 15 }
sub XSCON_TYPE_ARRAYREF       () { 16 }
sub XSCON_TYPE_HASHREF        () { 32 }

sub _pp_default {
    my ($object, $param) = @_;
    my $default = $param->{default};
    return $default->($object) if ref($default) eq 'CODE';
    if (ref($default) eq 'SCALAR') {
        my $builder = $$default;
        return $object->$builder;
    }
    return $default;
}

sub _pp_clone {
    require Clone;
    return Clone::clone($_[0]);
}

sub _pp_check_and_coerce {
    my ($param, $value) = @_;
    return $value unless $param->{check};
    return $value if $param->{check}->($value);
    if ($param->{coercion}) {
        my $coerced = $param->{coercion}->($value);
        return $coerced if $param->{check}->($coerced);
        die "Coercion result '$coerced' failed type constraint for '$param->{name}'";
    }
    die "Value '$value' failed type constraint for '$param->{name}'";
}

sub _pp_initialize {
    my ($object, $args, $meta) = @_;
    my %used;
    for my $param (@{ $meta->{params} || [] }) {
        my $name = $param->{name};
        my @input_names;
        push @input_names, $param->{init_arg} if defined $param->{init_arg};
        push @input_names, @{ $param->{aliases} || [] };
        my @found = grep { exists $args->{$_} } @input_names;
        die "Superfluous alias used for attribute '$name': $found[1]"
            if @found > 1;

        my ($has_value, $from_args, $value);
        if (@found) {
            $used{$found[0]} = 1;
            $value = $args->{ $found[0] };
            $has_value = 1;
            $from_args = 1;
            $has_value = 0
                if $param->{undef_tolerant} && !defined $value;
        }
        if (!$has_value && exists $param->{default}) {
            $value = _pp_default($object, $param);
            $has_value = 1;
        }
        if (!$has_value && $param->{required}) {
            die defined($param->{init_arg}) && $param->{init_arg} ne $name
                ? "Attribute '$name' (init arg '$param->{init_arg}') is required"
                : "Attribute '$name' is required";
        }
        next unless $has_value;

        $value = _pp_check_and_coerce($param, $value);
        if ($from_args && $param->{clone_on_write}) {
            $value = ref($param->{clone_on_write}) eq 'CODE'
                ? $param->{clone_on_write}->($object, $name, $value)
                : _pp_clone($value);
            if ($param->{check} && !$param->{check}->($value)) {
                die "Cloning result '$value' failed type constraint for '$name'";
            }
        }
        if ($param->{slot_initializer}) {
            $param->{slot_initializer}->($object, $value);
        }
        else {
            $object->{$name} = $value;
        }
        if ($from_args && $param->{trigger}) {
            my $mutex = "$name:trigger_mutex";
            if (!exists $object->{$mutex}) {
                local $object->{$mutex} = 1;
                if (ref($param->{trigger}) eq 'CODE') {
                    $param->{trigger}->($object, $value);
                }
                else {
                    my $trigger = $param->{trigger};
                    $object->$trigger($value);
                }
            }
        }
        if ($param->{spec}{weak_ref} && ref $object->{$name}) {
            require Scalar::Util;
            Scalar::Util::weaken($object->{$name});
        }
    }
    return \%used;
}

sub _pp_buildall {
    my ($object, $args) = @_;
    return $object if delete($args->{__no_BUILD__});
    for my $build (Class::XSConstructor::get_build_methods(ref $object)) {
        $build->($object, $args);
    }
    return $object;
}

sub install_constructor {
    my ($name, $buildall_name, $clear_name) = @_;
    (my $defining_class = $name) =~ s/::[^:]+$//;
    no strict 'refs';
    *{$name} = sub {
        my $invocant = shift;
        my $class = ref($invocant) || $invocant;
        my $meta = Class::XSConstructor::get_metadata($defining_class)
            or die "No Class::XSConstructor metadata for $defining_class";
        my @original = @_;
        my $args = $meta->{buildargs}
            ? $class->BUILDARGS(@_)
            : (@_ == 1 && ref($_[0]) eq 'HASH' ? { %{ $_[0] } } : { @_ });
        die 'BUILDARGS did not return a hashref' unless ref($args) eq 'HASH';
        my $object;
        if ($meta->{foreignbuildall}) {
            # Moo/Moose-style parent constructors accept the normalized hashref
            # and run their own BUILDALL.  Suppress that pass so the subclass's
            # BUILDALL below invokes every BUILD method exactly once.
            $args->{__no_BUILD__} = 1 unless exists $args->{__no_BUILD__};
            $object = $meta->{foreignconstructor}->($class, $args);
            delete $args->{__no_BUILD__};
            bless $object, $class;
        }
        elsif ($meta->{foreignconstructor}) {
            my @foreign = $meta->{foreignbuildargs}
                ? $class->FOREIGNBUILDARGS(@original) : @original;
            $object = $meta->{foreignconstructor}->($class, @foreign);
            bless $object, $class;
        }
        else {
            $object = bless {}, $class;
        }
        my $used = _pp_initialize($object, $args, $meta);
        _pp_buildall($object, $args);
        if ($meta->{strict_params}) {
            my %allowed = map { $_ => 1 } @{ $meta->{allow} || [] };
            my @unknown = grep {
                !$used->{$_} && !$allowed{$_} && $_ ne '__no_BUILD__'
            } keys %$args;
            die "Found unknown attribute" . (@unknown == 1 ? '' : 's')
                . " passed to the constructor: " . join(', ', sort @unknown)
                if @unknown;
        }
        return $object;
    };
    *{$buildall_name} = sub { _pp_buildall($_[0], $_[1] || {}) };
    *{$clear_name} = sub { $_[0] };
    return;
}

sub install_destructor {
    my ($name, $demolishall_name, $clear_name) = @_;
    no strict 'refs';
    *{$demolishall_name} = sub {
        my $object = shift;
        $_->($object, @_) for Class::XSConstructor::get_demolish_methods(ref $object);
        return;
    };
    *{$name} = sub {
        my $object = shift;
        local $@;
        eval { $object->$demolishall_name(0) };
        return;
    };
    *{$clear_name} = sub { $_[0] };
    return;
}

sub install_reader {
    my ($name, $slot, $has_default, $default_flags, $default, $check_flags,
        $check, $coercion, $cloner) = @_;
    no strict 'refs';
    *{$name} = sub {
        my $object = shift;
        if ($has_default && !exists $object->{$slot}) {
            my $param = { name => $slot, default => $default,
                check => $check, coercion => $coercion };
            $object->{$slot} = _pp_check_and_coerce(
                $param, _pp_default($object, $param),
            );
        }
        my $value = $object->{$slot};
        return !$cloner ? $value
            : ref($cloner) eq 'CODE' ? $cloner->($object, $slot, $value)
            : _pp_clone($value);
    };
    return;
}

sub install_delegation {
    my ($name, $slot, $method, $curried, $is_accessor, $is_try) = @_;
    no strict 'refs';
    *{$name} = sub {
        my $object = shift;
        my $handler = $is_accessor ? $object->$slot : $object->{$slot};
        require Scalar::Util;
        return undef if $is_try && !Scalar::Util::blessed($handler);
        die 'Expected blessed object to delegate to; got '
            . (defined($handler) ? $handler : 'undef')
            unless Scalar::Util::blessed($handler);
        return $handler->$method(@{ $curried || [] }, @_);
    };
    return;
}

sub clone { _pp_clone($_[0]) }

1;
