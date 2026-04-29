package namespace::autoclean;

use strict;
use warnings;

our $VERSION = '0.31';

# namespace::autoclean for PerlOnJava
#
# Removes imported functions from a package's namespace at end of scope,
# keeping locally-defined methods. Uses Sub::Util::subname (via XSLoader)
# to determine whether a function was imported or defined locally.

use B::Hooks::EndOfScope 'on_scope_end';
use List::Util 'first';

# Load the XS Sub::Util implementation directly to avoid CPAN version conflicts
BEGIN {
    require XSLoader;
    XSLoader::load('Sub::Util', '1.63');
}

sub import {
    my ($class, %args) = @_;

    my $subcast = sub {
        my $i = shift;
        return $i if ref $i eq 'CODE';
        return sub { $_ =~ $i } if ref $i eq 'Regexp';
        return sub { $_ eq $i };
    };

    my $runtest = sub {
        my ($code, $method_name) = @_;
        local $_ = $method_name;
        return $code->();
    };

    my $cleanee = exists $args{-cleanee} ? $args{-cleanee} : scalar caller;

    my @also = map $subcast->($_), (
        exists $args{-also}
        ? (ref $args{-also} eq 'ARRAY' ? @{ $args{-also} } : $args{-also})
        : ()
    );

    my @except = map $subcast->($_), (
        exists $args{-except}
        ? (ref $args{-except} eq 'ARRAY' ? @{ $args{-except} } : $args{-except})
        : ()
    );

    on_scope_end {
        my $subs = _get_functions($cleanee);
        my $method_check = _method_check($cleanee);

        my @clean = grep {
            my $method = $_;
            ! first { $runtest->($_, $method) } @except
            and (
                !$method_check->($method)
                or first { $runtest->($_, $method) } @also
            )
        } keys %$subs;

        # Remove cleaned functions from the stash
        if (@clean) {
            no strict 'refs';
            for my $func (@clean) {
                # Save non-CODE slots (scalars, arrays, hashes, etc.)
                my $glob = *{"${cleanee}::${func}"};
                my @saved;
                for my $slot (qw(SCALAR ARRAY HASH IO FORMAT)) {
                    my $ref = *{$glob}{$slot};
                    push @saved, [$slot, $ref] if defined $ref;
                }

                # Delete the glob entirely
                delete ${"${cleanee}::"}{$func};

                # Restore non-CODE slots
                for my $pair (@saved) {
                    my ($slot, $ref) = @$pair;
                    # Recreate the glob with just the non-CODE slots
                    if ($slot eq 'SCALAR' && defined $$ref) {
                        *{"${cleanee}::${func}"} = $ref;
                    } elsif ($slot eq 'ARRAY' && @$ref) {
                        *{"${cleanee}::${func}"} = $ref;
                    } elsif ($slot eq 'HASH' && %$ref) {
                        *{"${cleanee}::${func}"} = $ref;
                    }
                }
            }
        }
    };
}

# Get all functions in a package
sub _get_functions {
    my $package = shift;
    my %subs;
    no strict 'refs';
    for my $name (keys %{"${package}::"}) {
        next if $name =~ /^[A-Z]+$/;  # Skip special names like BEGIN, END, etc.
        my $glob = ${"${package}::"}{$name};
        # Check if the glob has a CODE slot
        if (defined &{"${package}::${name}"}) {
            $subs{$name} = \&{"${package}::${name}"};
        }
    }
    return \%subs;
}

# Check if a function is a "method" (defined locally vs imported)
sub _method_check {
    my $package = shift;

    # For Moose/Moo classes, use the metaclass if available
    if (defined &Class::MOP::class_of) {
        my $meta = Class::MOP::class_of($package);
        # Only metaclasses that mix in HasMethods (Class::MOP::Class and friends)
        # implement get_method_list. A bare Class::MOP::Package instance does not,
        # so guard with can() to avoid "Can't locate object method" errors during
        # on_scope_end callbacks (seen with MooseX::Types loading namespace::autoclean
        # in non-class packages).
        if ($meta && $meta->can('get_method_list')) {
            my %methods = map +($_ => 1), $meta->get_method_list;
            $methods{meta} = 1
                if $meta->isa('Moose::Meta::Role')
                && eval { Moose->VERSION } < 0.90;
            return sub { $_[0] =~ /^\(/ || $methods{$_[0]} };
        }
    }

    # For plain classes: use subname to detect origin
    my $does = $package->can('does') ? 'does'
             : $package->can('DOES') ? 'DOES'
             : undef;

    return sub {
        return 1 if $_[0] =~ /^\(/;  # Overloaded operators

        my $coderef = do { no strict 'refs'; \&{"${package}::$_[0]"} };
        my $fullname = Sub::Util::subname($coderef);
        return 1 unless defined $fullname;  # Can't determine origin, keep it

        my ($code_stash) = $fullname =~ /\A(.*)::/s;
        return 1 unless defined $code_stash;

        return 1 if $code_stash eq $package;   # Defined locally
        return 1 if $code_stash eq 'constant'; # Constant subs
        # Companion/helper packages (e.g. DateTime::PP for DateTime) install
        # functions via glob assignment — these are intentional methods, not imports.
        # In PerlOnJava, method calls are resolved at runtime through the stash,
        # so we must not remove them.
        return 1 if index($code_stash, "${package}::") == 0;  # Companion package
        return 1 if $does && eval { $package->$does($code_stash) };  # Role methods

        return 0;  # Imported - clean it
    };
}

1;

__END__

=head1 NAME

namespace::autoclean - Keep imports out of your namespace

=head1 SYNOPSIS

    package Foo;
    use namespace::autoclean;
    use Some::Exporter qw(imported_function);
    
    sub bar { imported_function('stuff') }
    
    # later:
    Foo->bar;               # works
    Foo->imported_function; # fails - cleaned after compilation

=head1 DESCRIPTION

When you import a function into a Perl package, it will naturally also be
available as a method. The C<namespace::autoclean> pragma will remove all
imported symbols at the end of the current package's compile cycle. Functions
called in the package itself will still be bound by their name, but they won't
show up as methods on your class or instances.

=head1 PARAMETERS

=head2 -cleanee => $package

Specify which package to clean (defaults to caller).

=head2 -also => ITEM | REGEX | SUB | ARRAYREF

Additional functions to clean.

=head2 -except => ITEM | REGEX | SUB | ARRAYREF

Functions to exclude from cleaning.

=cut
