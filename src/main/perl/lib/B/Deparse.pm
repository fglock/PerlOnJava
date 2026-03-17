package B::Deparse;
use strict;
use warnings;

our $VERSION = '1.00_perlonjava';

# B::Deparse stub for PerlOnJava
# In Perl, B::Deparse decompiles bytecode back to Perl source.
# In PerlOnJava, we compile to JVM bytecode which cannot be decompiled to Perl.
# 
# This stub provides minimal functionality:
# 1. For Sub::Quote created subs, return the stored source code
# 2. For other subs, return a placeholder

sub new {
    my $class = shift;
    my %opts = @_;
    bless \%opts, $class;
}

sub coderef2text {
    my ($self, $coderef) = @_;
    
    return '{ "DUMMY" }' unless ref($coderef) eq 'CODE';
    
    # If Sub::Defer is loaded, check if this is a deferred sub and undefer it first
    if (defined &Sub::Defer::undefer_sub) {
        my $undeferred = Sub::Defer::undefer_sub($coderef);
        $coderef = $undeferred if defined $undeferred;
    }
    
    # Try to get source from Sub::Quote if available
    if (defined &Sub::Quote::quoted_from_sub) {
        my $info = Sub::Quote::quoted_from_sub($coderef);
        if ($info && ref($info) eq 'ARRAY' && defined $info->[1]) {
            my $source = $info->[1];
            # Strip only the FIRST PRELUDE that Sub::Quote adds
            # The prelude ends with "# END quote_sub PRELUDE\n"
            # Use non-greedy match (.*?) to match only the first prelude
            if ($source =~ s/^.*?# END quote_sub PRELUDE\n//s) {
                # Successfully stripped prelude
            } else {
                # Fallback: try to strip comments and package/BEGIN blocks
                $source =~ s/^#.*\n//mg;  # Remove comment lines
                $source =~ s/^\s*package\s+\S+;\s*//;  # Remove package declaration
            }
            $source =~ s/^\s+//;  # Trim leading whitespace
            $source =~ s/\s+$//;  # Trim trailing whitespace
            return "{\n$source\n}";
        }
    }
    
    # Fallback: return a placeholder
    # In real Perl, B::Deparse would decompile the optree
    return '{ "DUMMY" }';
}

# Additional methods that might be called
sub ambient_pragmas { }
sub indent_size { $_[0]->{indent_size} // 4 }

1;

__END__

=head1 NAME

B::Deparse - Stub implementation for PerlOnJava

=head1 SYNOPSIS

    use B::Deparse;
    my $deparse = B::Deparse->new;
    my $text = $deparse->coderef2text(\&some_sub);

=head1 DESCRIPTION

This is a stub implementation of B::Deparse for PerlOnJava.

In Perl, B::Deparse decompiles Perl's internal optree back to Perl source code.
In PerlOnJava, code is compiled to JVM bytecode, which cannot be decompiled
back to Perl.

This stub provides minimal functionality:

=over 4

=item *

For subroutines created via Sub::Quote, the stored source code is returned.

=item *

For other subroutines, a placeholder C<{ "DUMMY" }> is returned.

=back

=head1 LIMITATIONS

Most B::Deparse functionality is not implemented. This stub only provides
enough to allow code that uses B::Deparse to load without errors.

=cut
