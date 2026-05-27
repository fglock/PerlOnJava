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
# 2. For simple anonymous subs and prototype blocks whose source file is
#    still available, return the source-visible body
# 3. For other subs, return a placeholder

sub new {
    my $class = shift;
    my %opts;
    while (@_) {
        my $opt = shift;
        $opts{$opt} = (@_ && $opt !~ /^-/) ? shift : 1;
    }
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

    my $source = _source_visible_anon_sub($coderef);
    return $source if defined $source;
    
    # Fallback: return a placeholder
    # In real Perl, B::Deparse would decompile the optree
    return '{ "DUMMY" }';
}

sub _source_visible_anon_sub {
    my ($coderef) = @_;

    require B;
    my $cv = eval { B::svref_2object($coderef) } or return;
    my $cop = eval { $cv->START } or return;
    my $file = eval { $cop->file } or return;
    my $line = eval { $cop->line } || 0;
    return if $line <= 0 || $file eq '-e' || !-f $file;

    open my $fh, '<', $file or return;
    my @lines = <$fh>;
    close $fh;

    my $source = join '', @lines;
    return _extract_source_visible_block($source, $line);
}

sub _extract_source_visible_block {
    my ($source, $target_line) = @_;

    my @stack;
    my @candidates;
    my $line = 1;
    for (my $i = 0; $i < length($source); $i++) {
        my $ch = substr($source, $i, 1);
        if ($ch eq '{') {
            push @stack, [$i, $line, _looks_like_code_block_open($source, $i)];
        } elsif ($ch eq '}') {
            my $open = pop @stack;
            next unless $open;
            my ($start, $start_line, $looks_like_code) = @$open;
            next unless $looks_like_code;
            next unless $start_line <= $target_line && $target_line <= $line;
            push @candidates, [$start, $i];
        } elsif ($ch eq "\n") {
            $line++;
        }
    }
    return unless @candidates;

    @candidates = sort {
        ($a->[1] - $a->[0]) <=> ($b->[1] - $b->[0])
            || $b->[0] <=> $a->[0]
    } @candidates;
    my ($brace, $end) = @{$candidates[0]};

    my $body = substr($source, $brace + 1, $end - $brace - 1);
    $body =~ s/^\s+//;
    $body =~ s/\s+$//;
    $body .= ';' if length($body) && $body !~ /[;}]\z/;
    return "{ $body }";
}

sub _looks_like_code_block_open {
    my ($source, $brace) = @_;
    my $prefix = substr($source, 0, $brace);
    $prefix =~ s/[ \t\r\n]+\z//;

    return 1 if $prefix =~ /\bsub\s*(?:\([^)]*\)\s*)?\z/;
    return 1 if $prefix =~ /(?:^|[^\w:\$\@\%])(?:[A-Za-z_]\w*(?:::[A-Za-z_]\w*)*)\z/;
    return 0;
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

For simple anonymous subroutines and prototype blocks whose source file is
still available, the source-visible body is returned.

=item *

For other subroutines, a placeholder C<{ "DUMMY" }> is returned.

=back

=head1 LIMITATIONS

Most B::Deparse functionality is not implemented. This stub only provides
enough to allow code that uses B::Deparse to load without errors.

=cut
