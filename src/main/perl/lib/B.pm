package B;

# Minimal B module for PerlOnJava
# Provides basic functionality for Test2

use strict;
use warnings;

our $VERSION = '1.00_perlonjava';

# Flag indicating this is a stub implementation with limited introspection
our $INCOMPLETE = 1;

# CV flags
use constant {
    CVf_ANON      => 0x0004,
    CVf_UNIQUE    => 0x0008,
    CVf_METHOD    => 0x0040,
    CVf_ISXSUB    => 0x0080,
};

# Stub classes for B objects
package B::SV {
    sub new {
        my ($class, $ref) = @_;
        return bless { ref => $ref }, $class;
    }
}

package B::CV {
    our @ISA = ('B::SV');
    
    sub GV {
        my $self = shift;
        return B::GV->new("__ANON__", "main");
    }
    
    sub STASH {
        my $self = shift;
        return B::STASH->new("main");
    }
    
    sub FILE {
        return "-e";
    }
    
    sub START {
        return B::OP->new();
    }
    
    sub SV {
        my $self = shift;
        return B::SV->new($self->{ref});
    }
    
    sub CvFLAGS {
        # Always return CVf_ANON for stubs
        return 0x0004;  # CVf_ANON
    }
}

package B::GV {
    our @ISA = ('B::SV');
    
    sub new {
        my ($class, $name, $pkg) = @_;
        return bless { name => $name, package => $pkg }, $class;
    }
    
    sub NAME {
        my $self = shift;
        return $self->{name};
    }
    
    sub STASH {
        my $self = shift;
        return B::STASH->new($self->{package});
    }
}

package B::STASH {
    sub new {
        my ($class, $pkg) = @_;
        return bless { package => $pkg }, $class;
    }
    
    sub NAME {
        my $self = shift;
        return $self->{package};
    }
}

package B::OP {
    sub new {
        my $class = shift;
        return bless { line => 1 }, $class;
    }
    
    sub line {
        my $self = shift;
        return $self->{line};
    }
    
    sub next {
        # Return undef to terminate traversal
        return;
    }
}

package B;

# Main introspection function
sub svref_2object {
    my $ref = shift;
    my $type = ref($ref);

    # A plain CODE scalar (e.g. from \&f in interpreter mode) has ref() eq 'CODE'.
    # A CODE-typed scalar passed directly (not wrapped in REFERENCE) also needs
    # to be treated as a CV — detect it via Scalar::Util::reftype as well.
    if ($type eq 'CODE') {
        return B::CV->new($ref);
    }

    # Scalar::Util::reftype sees through blessing; use it as a fallback
    # for cases where ref() returns a package name (blessed code ref).
    require Scalar::Util;
    my $rtype = Scalar::Util::reftype($ref) // '';
    if ($rtype eq 'CODE') {
        return B::CV->new($ref);
    }

    return B::SV->new($ref);
}

# Export CVf_ANON as a function
sub CVf_ANON() { return 0x0004; }

# Special SV names
our @specialsv_name = ('Nullsv', '&PL_sv_undef', '&PL_sv_yes', '&PL_sv_no');

1;

__END__

=head1 NAME

B - Minimal stub implementation for PerlOnJava

=head1 SYNOPSIS

    use B;
    my $cv = B::svref_2object(\&some_sub);
    print $cv->GV->NAME;

=head1 DESCRIPTION

This is an incomplete stub implementation of the B module for PerlOnJava.
It provides minimal functionality for Test2::Util::Sub but cannot provide
accurate introspection of code references.

The module sets C<$B::INCOMPLETE = 1> to indicate it should not be used 
for tests requiring full B functionality.

However, the classes and functions are still available for code (like Test2)
that can work with limited B functionality.

=head1 LIMITATIONS

- Cannot extract actual subroutine names from code references  
- Cannot determine actual package names
- CV flags are generic
- File locations are not tracked

=cut

# Note: In a complete Perl implementation, this module would return false (0;)
# to make tests skip B-dependent functionality, but PerlOnJava's require doesn't
# check return values yet. The module is intentionally incomplete.
1;
