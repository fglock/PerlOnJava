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

    # Runtime-created CVs may not expose a B::CV START op, but the compiler
    # still retains an exact source span for them.
    my ($runtime_source, $runtime_flags, $runtime_offset, $runtime_end)
        = _runtime_deparse_info($coderef);
    my $runtime_format_flags = $self->{ambient_pragmas} ? 0 : $runtime_flags;
    my $runtime_span = _extract_runtime_source_span(
        $runtime_source, $runtime_flags, $runtime_offset, $runtime_end,
        $runtime_format_flags);
    return $runtime_span if defined $runtime_span;
    if (defined $runtime_source && length $runtime_source) {
        my ($runtime_file, $runtime_line) =
            eval { Internals::jperl_cv_start_info($coderef) };
        my $block = _extract_source_visible_block(
            $runtime_source, $runtime_line || 0, $runtime_flags, $runtime_offset);
        return $block if defined $block;
    }

    my $source = _source_visible_anon_sub($coderef, $runtime_format_flags);
    return $source if defined $source;
    
    # Fallback: return a placeholder
    # In real Perl, B::Deparse would decompile the optree
    return '{ "DUMMY" }';
}

sub _extract_runtime_source_span {
    my ($source, $flags, $offset, $end, $format_flags) = @_;
    return unless defined $source && length $source;
    return unless defined $offset && $offset >= 0;
    return if $offset >= length($source);
    if (!defined($end) || $end <= $offset) {
        my $open = index($source, '{', $offset);
        return if $open < 0;
        my ($depth, $quote, $escaped) = (0, '', 0);
        for (my $i = $open; $i < length($source); $i++) {
            my $ch = substr($source, $i, 1);
            if ($quote ne '') {
                if ($escaped) { $escaped = 0 }
                elsif ($ch eq '\\') { $escaped = 1 }
                elsif ($ch eq $quote) { $quote = '' }
                next;
            }
            if ($ch eq '"' || $ch eq "'") { $quote = $ch; next }
            $depth++ if $ch eq '{';
            if ($ch eq '}' && --$depth == 0) { $end = $i + 1; last }
        }
    }
    return unless defined $end && $end > $offset;
    # Some AST nodes report the first expression in the body rather than the
    # `sub` token.  The end offset is still the compiler's exact boundary, so
    # anchor the slice at the nearest preceding block opener.
    my $span_offset = $offset;
    if (substr($source, $span_offset, 1) ne '{') {
        my $open = rindex($source, '{', $span_offset);
        $span_offset = $open if $open >= 0;
    }
    my $span = substr($source, $span_offset, $end - $span_offset);
    $span =~ s/^.*?(\{)/$1/s;
    $span =~ s/\s+$//;
    my $close = rindex($span, '}');
    $span = substr($span, 0, $close + 1) if $close >= 0;
    return unless $span =~ /\A\{.*\}\z/s;

    # Reuse the normal source-block formatter. The synthetic `sub` prefix
    # makes its existing AST/source-block detection select this exact span.
    $format_flags = $flags unless defined $format_flags;
    return _extract_source_visible_block("sub $span", 1, $format_flags, 4);
}

sub _source_visible_anon_sub {
    my ($coderef, $format_flags) = @_;

    require B;
    my $cv = eval { B::svref_2object($coderef) } or return;
    my $cop = eval { $cv->START } or return;
    my $file = eval { $cop->file } or return;
    my $line = eval { $cop->line } || 0;
    return if $line <= 0;

    my ($runtime_source, $flags, $source_offset, $source_end) = _runtime_deparse_info($coderef);
    if (defined $runtime_source && length $runtime_source) {
        my $span = _extract_runtime_source_span(
            $runtime_source, $flags, $source_offset, $source_end,
            $format_flags);
        return $span if defined $span;
        my $block = _extract_source_visible_block($runtime_source, $line, $flags, $source_offset);
        return $block if defined $block;
    }

    return if $file eq '-e' || !-f $file;

    open my $fh, '<', $file or return;
    my @lines = <$fh>;
    close $fh;

    my $source = join '', @lines;
    $format_flags = $flags unless defined $format_flags;
    return _extract_source_visible_block($source, $line, $format_flags, $source_offset);
}

sub _runtime_deparse_info {
    my ($coderef) = @_;

    return unless eval { require Internals; 1 };
    my @info = eval { Internals::jperl_cv_deparse_info($coderef) };
    return unless @info;
    return @info;
}

sub _extract_source_visible_block {
    my ($source, $target_line, $flags, $source_offset) = @_;

    my @stack;
    my @candidates;
    my $line = 1;
    my $line_start = 0;
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
            my $line_text = substr($source, $line_start, $i - $line_start);
            if ($line_text =~ /^\s*#line\s+(\d+)/) {
                $line = $1;
            } else {
                $line++;
            }
            $line_start = $i + 1;
        }
    }
    return unless @candidates;

    if (defined $source_offset && $source_offset >= 0) {
        my @containing = grep { $_->[0] <= $source_offset && $source_offset <= $_->[1] } @candidates;
        if (@containing) {
            @candidates = @containing;
        } else {
            my @after = grep { $_->[0] >= $source_offset } @candidates;
            @candidates = @after if @after;
        }
    }

    @candidates = sort {
        ($a->[1] - $a->[0]) <=> ($b->[1] - $b->[0])
            || $b->[0] <=> $a->[0]
    } @candidates;
    my ($brace, $end) = @{$candidates[0]};

    my $body = substr($source, $brace + 1, $end - $brace - 1);
    $body =~ s/^\s+//;
    $body =~ s/\s+$//;
    return if defined($source_offset) && $source_offset == -1
        && $body =~ /\A\{\s*use\s+(?:strict|warnings)\b/s;
    $body .= ';' if length($body) && $body !~ /[;}]\z/;
    if ($flags) {
        my @lines;
        push @lines, 'use warnings;' if $flags & 2;
        push @lines, 'use strict;' if $flags & 1;
        push @lines, split /\n/, $body;
        return "{\n" . join('', map { "    $_\n" } @lines) . "}";
    }
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
sub ambient_pragmas {
    my ($self, %pragmas) = @_;
    $self->{ambient_pragmas} = 1 if %pragmas;
    return $self;
}
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
