package warnings;

use strict;

our $VERSION = '0.01';

my %Warnings = (
    'all'           => 0x1FFFFFFF, # All warnings on
    'io'            => 0x00000001, # I/O operations
    'syntax'        => 0x00000002, # Syntax issues
    'uninitialized' => 0x00000004, # Use of uninitialized values
    'deprecated'    => 0x00000008, # Use of deprecated features
    'numeric'       => 0x00000010, # Numeric conversions
    'recursion'     => 0x00000020, # Deep recursion
    'redefine'      => 0x00000040, # Subroutine redefinition
    'bareword'      => 0x00000080, # Bareword issues
    'void'          => 0x00000100, # Useless use in void context
);

my $Bits = 0;

sub import {
    my $class = shift;
    my @args = @_ ? @_ : qw(all);

    for my $arg (@args) {
        if ($arg eq ':all') {
            $Bits |= $Warnings{'all'};
        }
        elsif (exists $Warnings{$arg}) {
            $Bits |= $Warnings{$arg};
        }
        else {
            die "Unknown warnings category '$arg'";
        }
    }
}

sub unimport {
    my $class = shift;
    my @args = @_ ? @_ : qw(all);

    for my $arg (@args) {
        if ($arg eq ':all') {
            $Bits = 0;
        }
        elsif (exists $Warnings{$arg}) {
            $Bits &= ~$Warnings{$arg};
        }
        else {
            die "Unknown warnings category '$arg'";
        }
    }
}

sub enabled {
    my ($category) = @_;
    return unless exists $Warnings{$category};
    return $Bits & $Warnings{$category};
}

1;
