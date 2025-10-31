package B;

# Minimal B module stub for PerlOnJava
# Provides just enough functionality for Test2 introspection

use strict;
use warnings;

our $VERSION = '1.00_stub';

# Special SV names array (used by Test2::Util::Stash)
our @specialsv_name = qw(Nullsv &PL_sv_undef &PL_sv_yes &PL_sv_no);

# Base class for all B objects
package B::SV;
sub new {
    my ($class, $ref) = @_;
    return bless { ref => $ref }, $class;
}

package B::SPECIAL;
our @ISA = qw(B::SV);

package B::NULL;
our @ISA = qw(B::SPECIAL);

package B::PV;
our @ISA = qw(B::SV);

package B::IV;
our @ISA = qw(B::PV);

package B::NV;
our @ISA = qw(B::IV);

package B::RV;
our @ISA = qw(B::SV);

package B::PVMG;
our @ISA = qw(B::PV);

# GV - Glob Value (represents a typeglob)
package B::GV;
our @ISA = qw(B::SV);

sub new {
    my ($class, $name, $package) = @_;
    return bless {
        name => $name,
        package => $package,
    }, $class;
}

sub NAME {
    my $self = shift;
    return $self->{name} || '__ANON__';
}

sub STASH {
    my $self = shift;
    return B::STASH->new($self->{package} || 'main');
}

# STASH - Package symbol table
package B::STASH;

sub new {
    my ($class, $package) = @_;
    return bless { package => $package }, $class;
}

sub NAME {
    my $self = shift;
    return $self->{package} || 'main';
}

# OP - Operation node (simplified - no real op tree traversal)
package B::OP;

sub new {
    my ($class) = @_;
    return bless { line => 0 }, $class;
}

sub line {
    my $self = shift;
    return $self->{line} || 1;
}

sub next {
    # Return undef to terminate op tree traversal
    return undef;
}

# CV - Code Value (represents a subroutine)
package B::CV;
our @ISA = qw(B::SV);

sub new {
    my ($class, $coderef) = @_;
    
    # Try to extract info from the coderef
    my ($package, $name, $file) = ('main', '__ANON__', '-e');
    
    # Attempt to get CV info from Perl internals
    # In PerlOnJava, we'll use RuntimeScalar metadata if available
    if (ref($coderef) eq 'CODE') {
        # Try to get package and name from the coderef stringification
        my $str = "$coderef";
        if ($str =~ /CODE\(0x[0-9a-f]+\)/) {
            # Anonymous sub - keep defaults
        }
        # For now, we use defaults - could be enhanced later
    }
    
    return bless {
        ref => $coderef,
        package => $package,
        name => $name,
        file => $file,
    }, $class;
}

sub GV {
    my $self = shift;
    return B::GV->new($self->{name}, $self->{package});
}

sub FILE {
    my $self = shift;
    return $self->{file} || '-e';
}

sub START {
    my $self = shift;
    # Return a dummy OP that will immediately terminate traversal
    return B::OP->new();
}

sub SV {
    my $self = shift;
    # Return a generic SV
    return B::SV->new($self->{ref});
}

# Main B package functions
package B;

sub svref_2object {
    my ($ref) = @_;
    
    my $reftype = ref($ref);
    
    if ($reftype eq 'CODE') {
        return B::CV->new($ref);
    }
    elsif ($reftype eq 'GLOB' || $reftype eq 'GLOBREF') {
        # For globs, return a CV with SV method
        my $cv = B::CV->new(undef);
        return $cv;
    }
    elsif ($reftype eq 'SCALAR' || $reftype eq 'REF') {
        return B::SV->new($ref);
    }
    elsif ($reftype eq 'ARRAY') {
        return B::SV->new($ref);
    }
    elsif ($reftype eq 'HASH') {
        return B::SV->new($ref);
    }
    else {
        return B::SV->new($ref);
    }
}

1;

__END__

=head1 NAME

B - Minimal stub of the Perl compiler backend for PerlOnJava

=head1 SYNOPSIS

    use B;
    
    my $cv = B::svref_2object(\&some_sub);
    my $name = $cv->GV->NAME;
    my $package = $cv->GV->STASH->NAME;
    my $file = $cv->FILE;

=head1 DESCRIPTION

This is a minimal implementation of the B module for PerlOnJava, providing
just enough functionality for Test2 introspection needs. It does not provide
full compiler backend functionality.

=head1 LIMITATIONS

- No real op-tree traversal (START returns a stub that terminates immediately)
- Limited subroutine name extraction (defaults to __ANON__)
- No support for accessing PadList, Padname, or other compiler internals
- Simplified type hierarchy

This implementation is sufficient for Test2::Util::Sub and Test2::Util::Stash
basic functionality.

=cut

