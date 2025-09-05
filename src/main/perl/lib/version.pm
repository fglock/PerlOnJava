#!perl -w
package version;

use 5.006002;
use strict;
use warnings::register;
if ($] >= 5.015) {
    warnings::register_categories(qw/version/);
}

our $VERSION = '0.9933';
our $CLASS = 'version';
our (@ISA, $STRICT, $LAX);

BEGIN {
    # Load the XS implementation
    XSLoader::load('Version');
}

# Set up operator overloading
use overload (
    '""'  => \&stringify,
    '<=>' => \&vcmp,
    'cmp' => \&vcmp,
);

# avoid using Exporter
require version::regex;
*version::is_lax = \&version::regex::is_lax;
*version::is_strict = \&version::regex::is_strict;
*LAX = \$version::regex::LAX;
*LAX_DECIMAL_VERSION = \$version::regex::LAX_DECIMAL_VERSION;
*LAX_DOTTED_DECIMAL_VERSION = \$version::regex::LAX_DOTTED_DECIMAL_VERSION;
*STRICT = \$version::regex::STRICT;
*STRICT_DECIMAL_VERSION = \$version::regex::STRICT_DECIMAL_VERSION;
*STRICT_DOTTED_DECIMAL_VERSION = \$version::regex::STRICT_DOTTED_DECIMAL_VERSION;

sub import {
    no strict 'refs';
    my ($class) = shift;

    # Set up any derived class
    unless ($class eq $CLASS) {
        local $^W;
        *{$class.'::declare'} =  \&{$CLASS.'::declare'};
        *{$class.'::qv'} = \&{$CLASS.'::qv'};
    }

    my %args;
    if (@_) { # any remaining terms are arguments
        map { $args{$_} = 1 } @_
    }
    else { # no parameters at all on use line
        %args =
            (
                qv => 1,
                'UNIVERSAL::VERSION' => 1,
            );
    }

    my $callpkg = caller();

    if (exists($args{declare})) {
        *{$callpkg.'::declare'} =
            sub {return $class->declare(shift) }
            unless defined(&{$callpkg.'::declare'});
    }

    if (exists($args{qv})) {
        *{$callpkg.'::qv'} =
            sub {return $class->qv(shift) }
            unless defined(&{$callpkg.'::qv'});
    }

    if (exists($args{'UNIVERSAL::VERSION'})) {
        local $^W;
        *UNIVERSAL::VERSION
            = \&{$CLASS.'::_VERSION'};
    }

    if (exists($args{'VERSION'})) {
        *{$callpkg.'::VERSION'} = \&{$CLASS.'::_VERSION'};
    }

    if (exists($args{'is_strict'})) {
        *{$callpkg.'::is_strict'} = \&{$CLASS.'::is_strict'}
            unless defined(&{$callpkg.'::is_strict'});
    }

    if (exists($args{'is_lax'})) {
        *{$callpkg.'::is_lax'} = \&{$CLASS.'::is_lax'}
            unless defined(&{$callpkg.'::is_lax'});
    }
}

# Additional methods that might be needed for version objects

sub is_alpha {
    my ($self) = @_;
    return $self->{alpha} ? 1 : 0;
}

sub is_qv {
    my ($self) = @_;
    return $self->{qv} ? 1 : 0;
}

1;
