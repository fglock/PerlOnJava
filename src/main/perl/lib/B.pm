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
our @EXPORT_OK = qw(svref_2object class perlstring CVf_ANON SVf_IOK SVf_NOK SVf_POK SVp_IOK SVp_NOK SVp_POK);
our %EXPORT_TAGS = (
    all => \@EXPORT_OK,
);

# Flag indicating this is a stub implementation with limited introspection
our $INCOMPLETE = 1;

# SV flags - using standard Perl 5 values
use constant {
    SVf_IOK => 0x00000100,
    SVf_NOK => 0x00000200,
    SVf_POK => 0x00000400,
    SVp_IOK => 0x00001000,
    SVp_NOK => 0x00002000,
    SVp_POK => 0x00004000,
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
        # Return 1 as a reasonable default for compatibility.
        # This aligns with Internals::SvREFCNT() and Devel::Peek::SvREFCNT()
        # which also return 1, and makes is_oneref() checks pass.
        return 1;
    }

    sub RV {
        my $self = shift;
        my $r = $self->{ref};
        if (ref($r) eq 'SCALAR' || ref($r) eq 'REF') {
            return B::svref_2object($$r);
        }
        # For other reference types, return the referent wrapped
        return B::SV->new($r);
    }

    sub FLAGS {
        my $self = shift;
        my $r = $self->{ref};

        if (ref($r) eq 'SCALAR') {
            my $v = $$r;
            my $flags = 0;

            return 0 unless defined $v;

            # Use builtin introspection to determine creation type
            no warnings 'experimental::builtin';

            if (builtin::created_as_number($v)) {
                # Value was originally created as a number
                # Determine integer vs float
                no warnings 'numeric';
                if ($v == $v) {  # not NaN
                    # Check if it's an integer (no fractional part)
                    # Use int() comparison; Inf fails this check (good, it's NOK)
                    my $is_int = ($v == int($v)) && $v != 9**9**9 && $v != -9**9**9;
                    if ($is_int) {
                        $flags |= B::SVf_IOK() | B::SVp_IOK();
                    } else {
                        $flags |= B::SVf_NOK() | B::SVp_NOK();
                    }
                } else {
                    # NaN
                    $flags |= B::SVf_NOK() | B::SVp_NOK();
                }
            } elsif (length($v)) {
                # Value was created as a string (or is non-empty)
                $flags |= B::SVf_POK() | B::SVp_POK();
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

    # Introspect code reference to extract package and sub name
    sub _introspect {
        my $self = shift;
        return if $self->{_introspected};
        $self->{_introspected} = 1;
        $self->{_sub_name} = '__ANON__';
        $self->{_pkg_name} = 'main';
        $self->{_is_anon}  = 1;
        if ($self->{ref} && ref($self->{ref}) eq 'CODE') {
            eval { require Sub::Util };
            return if $@;  # Sub::Util not available, use defaults
            my $fqn = Sub::Util::subname($self->{ref});
            if (defined $fqn && $fqn ne '__ANON__') {
                # Split "Package::Name::subname" into package and name
                if ($fqn =~ /^(.+)::([^:]+)$/) {
                    my ($pkg, $name) = ($1, $2);
                    # Verify the sub still exists in the stash. Stubs whose
                    # stash entry has been deleted/cleared/undefined should be
                    # treated as anonymous (matching Perl 5's GV anonymization).
                    # Exception: CVs explicitly renamed via Sub::Name::subname
                    # (or Sub::Util::set_subname) carry a private flag; in
                    # real Perl their CvGV points to a free-floating GV with
                    # the assigned name, and NAME should always reflect that.
                    no strict 'refs';
                    my $renamed = 0;
                    if (exists $Sub::Name::{_is_renamed}) {
                        $renamed = Sub::Name::_is_renamed($self->{ref}) ? 1 : 0;
                    }
                    if ($renamed || defined &{"$fqn"}) {
                        $self->{_pkg_name} = $pkg;
                        $self->{_sub_name} = $name;
                        $self->{_is_anon}  = 0;
                    } else {
                        # Stash entry gone — extract package for STASH but
                        # keep NAME as __ANON__ and CVf_ANON set
                        $self->{_pkg_name} = $pkg;
                    }
                }
            }
        }
    }

    sub GV {
        my $self = shift;
        $self->_introspect;
        return B::GV->new($self->{_sub_name}, $self->{_pkg_name});
    }
    
    sub STASH {
        my $self = shift;
        $self->_introspect;
        return B::STASH->new($self->{_pkg_name});
    }
    
    sub FILE {
        return "-e";
    }
    
    sub START {
        # Return a B::COP (control op) so optree walkers find file/line info.
        # Real Perl returns the first op of the sub body; for PerlOnJava we
        # return a COP with the best location info we have.
        return B::COP->new("-e", 0);
    }
    
    sub ROOT {
        # PerlOnJava: all subs have bodies, return a real B::OP (not B::NULL)
        return B::OP->new();
    }

    sub const_sv {
        # Return a scalar ref to 0 so ${$cv->const_sv} is false
        return \0;
    }

    sub SV {
        my $self = shift;
        return B::SV->new($self->{ref});
    }
    
    sub CvFLAGS {
        my $self = shift;
        $self->_introspect;
        return $self->{_is_anon} ? 0x0004 : 0;  # CVf_ANON for anonymous subs
    }

    sub XSUB {
        # PerlOnJava has no XSUBs (all code is Java bytecode or interpreted Perl).
        # Return 0 (false) so callers like Type::Tiny::_has_xsub() get the right answer.
        return 0;
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

    sub SV {
        my $self = shift;
        my $glob = $self->{ref};
        if (defined $glob) {
            local $@;
            my $sv_val = eval { ${*{$glob}} };
            if (!$@ && defined $sv_val) {
                return B::SV->new(\${*{$glob}});
            }
        }
        return B::SPECIAL->new(0);  # 0 = index for 'Nullsv'
    }
}

package B::SPECIAL {
    sub new {
        my ($class, $index) = @_;
        return bless \$index, $class;
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
        # Return B::NULL to terminate traversal (matches real Perl behavior).
        # Code that walks the optree checks ref($op) eq "B::NULL" to stop.
        return B::NULL->new();
    }
}

package B::NULL {
    our @ISA = ('B::OP');
    sub new {
        my $class = shift;
        return bless {}, $class;
    }

    sub next {
        # NULL is terminal -- return self to prevent infinite loops
        return $_[0];
    }
}

package B::COP {
    # COP = "control op" -- carries file and line number metadata.
    # In real Perl, COP nodes are scattered through the optree at statement
    # boundaries. In PerlOnJava we synthesize one per CV with the best
    # location info available.
    our @ISA = ('B::OP');

    sub new {
        my ($class, $file, $line) = @_;
        return bless { file => $file // "-e", line => $line // 0 }, $class;
    }

    sub file {
        my $self = shift;
        return $self->{file};
    }

    sub line {
        my $self = shift;
        return $self->{line};
    }

    sub next {
        return B::NULL->new();
    }
}

package B;

# Utility: extract B:: class name from a B object
sub class {
    my $obj = shift;
    my $name = ref $obj;
    $name =~ s/^B:://;
    return $name;
}

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

    if ($rtype eq 'GLOB') {
        my $name = *{$ref}{NAME} // '';
        my $pkg  = *{$ref}{PACKAGE} // 'main';
        my $gv = B::GV->new($name, $pkg);
        $gv->{ref} = $ref;  # store glob ref for SV method access
        return $gv;
    }

    if ($type eq 'SCALAR') {
        return B::PVIV->new($ref);
    }

    return B::SV->new($ref);
}

# Export CVf_ANON as a function
sub CVf_ANON() { return 0x0004; }

# Export SVf_IOK as a function
sub SVf_IOK() { return 0x00000100; }

# Export SVf_NOK as a function
sub SVf_NOK() { return 0x00000200; }

# Export SVf_POK as a function
sub SVf_POK() { return 0x00000400; }

# Export SVp_IOK as a function
sub SVp_IOK() { return 0x00001000; }

# Export SVp_NOK as a function
sub SVp_NOK() { return 0x00002000; }

# Export SVp_POK as a function
sub SVp_POK() { return 0x00004000; }

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

- CV flags are generic (only CVf_ANON is set for anonymous subs)
- File locations are not tracked
- Anonymous subs report package as 'main' and name as '__ANON__'

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
