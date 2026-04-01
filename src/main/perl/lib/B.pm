package B;

#      B.pm
#
#      Original Copyright (c) 1996, 1997, 1998 Malcolm Beattie
#
#      You may distribute under the terms of either the GNU General Public
#      License or the Artistic License, as specified in the README file.
#
#      This is a minimal stub implementation for PerlOnJava.
#      PerlOnJava implementation by Flavio S. Glock.
#
# Minimal B module for PerlOnJava
# Provides basic functionality for Test2

use strict;
use warnings;

our $VERSION = '1.88';

# Export functionality
use Exporter 'import';
our @EXPORT_OK = qw(svref_2object perlstring CVf_ANON SVf_IOK SVf_POK);
our %EXPORT_TAGS = (
    all => \@EXPORT_OK,
);

# Flag indicating this is a stub implementation with limited introspection
our $INCOMPLETE = 1;

# SV flags (very partial)
use constant {
    SVf_IOK => 0x0001,
    SVf_POK => 0x0002,
};

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

    sub REFCNT {
        # JVM uses tracing GC, not reference counting.
        # Return 0 to indicate objects are always reclaimable.
        return 0;
    }

    sub FLAGS {
        my $self = shift;
        my $r = $self->{ref};

        # For the debugger source arrays (@{"_<..."}), perl stores lines as PVIV with IOK.
        # This stub implementation marks any defined, non-empty scalar as having IOK.
        # Also mark strings with SVf_POK for CPAN::Meta::YAML compatibility.
        if (ref($r) eq 'SCALAR') {
            my $v = $$r;
            my $flags = 0;
            if (defined($v) && length($v)) {
                $flags |= B::SVf_IOK();
                # If the value is a string (not purely numeric), set POK
                $flags |= B::SVf_POK() unless Scalar::Util::looks_like_number($v);
            }
            return $flags;
        }

        return 0;
    }
}

package B::PVIV {
    our @ISA = ('B::SV');
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

    if ($type eq 'SCALAR') {
        return B::PVIV->new($ref);
    }

    return B::SV->new($ref);
}

# Export CVf_ANON as a function
sub CVf_ANON() { return 0x0004; }

# Export SVf_IOK as a function
sub SVf_IOK() { return 0x0001; }

# Export SVf_POK as a function
sub SVf_POK() { return 0x0002; }

# Convert a string to its Perl source representation
# This is used by modules like Specio for code generation
sub perlstring {
    my ($str) = @_;
    return 'undef' unless defined $str;
    
    # Escape special characters
    $str =~ s/\\/\\\\/g;
    $str =~ s/"/\\"/g;
    $str =~ s/\$/\\\$/g;    # Escape $ to prevent interpolation
    $str =~ s/\@/\\\@/g;    # Escape @ to prevent interpolation
    $str =~ s/\n/\\n/g;
    $str =~ s/\r/\\r/g;
    $str =~ s/\t/\\t/g;
    $str =~ s/\f/\\f/g;
    $str =~ s/\a/\\a/g;
    $str =~ s/\e/\\e/g;
    # Escape non-printable characters
    $str =~ s/([\x00-\x1f\x7f-\xff])/sprintf("\\x%02x", ord($1))/ge;
    
    return qq{"$str"};
}

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

=head1 AUTHOR

Original B module by Malcolm Beattie, C<mbeattie@sable.ox.ac.uk>

PerlOnJava implementation by Flavio S. Glock.

=head1 COPYRIGHT

Original Copyright (c) 1996, 1997, 1998 Malcolm Beattie

You may distribute under the terms of either the GNU General Public
License or the Artistic License, as specified in the README file.

=cut

# Note: In a complete Perl implementation, this module would return false (0;)
# to make tests skip B-dependent functionality, but PerlOnJava's require doesn't
# check return values yet. The module is intentionally incomplete.
1;
