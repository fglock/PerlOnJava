package Safe;

use strict;
use warnings;

our $VERSION = '2.44_perlonjava';

# Safe.pm stub for PerlOnJava
#
# This is a minimal implementation that provides the interface used by CPAN.pm.
# CPAN.pm uses Safe->reval() to evaluate trusted CPAN metadata (package indexes,
# CHECKSUMS files). Since this metadata comes from CPAN mirrors and is trusted,
# we use regular eval instead of actual sandboxing.
#
# Note: This does NOT provide actual code sandboxing/compartmentalization.
# Do not use this for evaluating untrusted code.

my $root_counter = 0;

sub new {
    my ($class, $namespace) = @_;
    $namespace //= "Safe::Root" . $root_counter++;
    bless {
        namespace => $namespace,
        permit    => {},
        deny      => {},
    }, $class;
}

sub reval {
    my ($self, $code, $strict) = @_;
    
    # For PerlOnJava, we trust CPAN metadata - no sandboxing needed
    # The $strict parameter is ignored (would enable 'use strict' in real Safe)
    
    # Wrap code to disable strict vars - CHECKSUMS files use $cksum without declaration
    # The 'no strict "vars"' must be part of the eval'd code itself
    my $wrapped = qq{no strict 'vars'; $code};
    
    # Simple eval - just return the result
    my $result = eval $wrapped;
    
    # If there was an error, return undef (caller can check $@)
    return $@ ? undef : $result;
}

# Evaluate and return list context
sub reval_list {
    my ($self, $code, $strict) = @_;
    
    my @result;
    my $ok = eval {
        @result = eval $code;
        1;
    };
    
    if (!$ok || $@) {
        return ();
    }
    
    return @result;
}

# Compile code (returns code ref)
sub rdo {
    my ($self, $file) = @_;
    return do $file;
}

# Permission methods - stubs that accept but ignore
sub permit {
    my ($self, @ops) = @_;
    $self->{permit}{$_} = 1 for @ops;
    return $self;
}

sub permit_only {
    my ($self, @ops) = @_;
    $self->{permit} = {};
    $self->{permit}{$_} = 1 for @ops;
    return $self;
}

sub deny {
    my ($self, @ops) = @_;
    $self->{deny}{$_} = 1 for @ops;
    return $self;
}

sub deny_only {
    my ($self, @ops) = @_;
    $self->{deny} = {};
    $self->{deny}{$_} = 1 for @ops;
    return $self;
}

# Mask methods - stubs
sub mask {
    my $self = shift;
    if (@_) {
        $self->{mask} = shift;
        return $self;
    }
    return $self->{mask} // '';
}

# Share variables with compartment - stub
sub share {
    my ($self, @vars) = @_;
    push @{$self->{share} //= []}, @vars;
    return $self;
}

sub share_from {
    my ($self, $pkg, $vars) = @_;
    push @{$self->{share_from}{$pkg} //= []}, @$vars;
    return $self;
}

# Get/set the namespace root
sub root {
    my $self = shift;
    if (@_) {
        $self->{namespace} = shift;
    }
    return $self->{namespace};
}

# Access a variable in the compartment (stub - just returns the glob)
sub varglob {
    my ($self, $varname) = @_;
    my $ns = $self->{namespace};
    no strict 'refs';
    return *{"${ns}::${varname}"};
}

# Wrap a code ref to run in compartment (stub - returns unwrapped)
sub wrap_code_ref {
    my ($self, $code) = @_;
    return $code;
}

# Wrap code refs in a data structure (stub - returns unchanged)
sub wrap_code_refs_within {
    my ($self, $data) = @_;
    return $data;
}

1;

__END__

=head1 NAME

Safe - PerlOnJava stub for Safe compartments

=head1 SYNOPSIS

    use Safe;
    
    my $compartment = Safe->new();
    my $result = $compartment->reval('1 + 1');  # Returns 2

=head1 DESCRIPTION

This is a stub implementation of Safe.pm for PerlOnJava. It provides the
interface used by CPAN.pm to evaluate trusted CPAN metadata.

B<WARNING>: This does NOT provide actual code sandboxing. The C<reval()>
method simply uses Perl's built-in C<eval>. Do not use this module to
evaluate untrusted code.

=head1 METHODS

=over 4

=item new($namespace)

Create a new Safe object. The optional $namespace is stored but not used
for actual compartmentalization.

=item reval($code)

Evaluate $code using regular eval and return the result.

=item permit(@ops), permit_only(@ops), deny(@ops), deny_only(@ops)

Accept opcode specifications but do not enforce them. These are no-ops
that exist for API compatibility.

=item root()

Get/set the namespace name.

=item share(@vars), share_from($package, \@vars)

Accept variable sharing specifications but do not enforce them.

=back

=head1 LIMITATIONS

This stub does not provide:

=over 4

=item * Actual code sandboxing

=item * Opcode restriction (requires Opcode.pm which needs Perl internals)

=item * Namespace isolation

=back

=head1 SEE ALSO

L<CPAN> - The module that uses this stub

=head1 COPYRIGHT

This is a PerlOnJava compatibility stub.

=cut
