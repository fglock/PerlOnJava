package ExtUtils::MM_Unix;
use strict;
use warnings;

our $VERSION = '7.70_perlonjava';

# MM_Unix provides Unix-specific methods for ExtUtils::MakeMaker.
# In PerlOnJava, we only implement the methods needed by CPAN.pm.

# parse_version - extract VERSION from a Perl file
sub parse_version {
    my($self,$parsefile) = @_;
    my $result;

    local $/ = "\n";
    local $_;
    open(my $fh, '<', $parsefile) or die "Could not open '$parsefile': $!";
    my $inpod = 0;
    while (<$fh>) {
        $inpod = /^=(?!cut)/ ? 1 : /^=cut/ ? 0 : $inpod;
        next if $inpod || /^\s*#/;
        chop;
        next if /^\s*(if|unless|elsif)/;
        if ( m{^ \s* package \s+ \w[\w\:\']* \s+ (v?[0-9._]+) \s* (;|\{)  }x ) {
            no warnings;
            $result = $1;
        }
        elsif ( m{(?<!\\) ([\$*]) (([\w\:\']*) \bVERSION)\b .* (?<![<>=!])\=[^=]}x ) {
            $result = $self->get_version($parsefile, $1, $2);
        }
        else {
          next;
        }
        last if defined $result;
    }
    close $fh;

    if ( defined $result && $result !~ /^v?[\d_\.]+$/ ) {
      require version;
      my $normal = eval { version->new( $result ) };
      $result = $normal if defined $normal;
    }
    if ( defined $result ) {
      $result = "undef" unless $result =~ m!^v?[\d_\.]+$!
                        or eval { version->parse( $result ) };
    }
    $result = "undef" unless defined $result;
    return $result;
}

# get_version - helper for parse_version
# Simplified implementation that avoids package block issues
sub get_version {
    my ($self, $parsefile, $sigil, $name) = @_;
    my $line = $_; # from the while() loop in parse_version
    # Clean up taint mode markers
    $line = $1 if $line =~ m{^(.+)}s;
    
    # Directly extract version from common patterns
    # Pattern 1: $VERSION = '1.23' or $VERSION = "1.23"
    if ($line =~ /\$VERSION\s*=\s*['"]([^'"]+)['"]/) {
        return $1;
    }
    # Pattern 2: $VERSION = 1.23 (bare number)
    if ($line =~ /\$VERSION\s*=\s*([\d._]+)/) {
        return $1;
    }
    # Pattern 3: version->new('v1.2.3') or version->declare('v1.2.3')
    if ($line =~ /version->(?:new|declare)\s*\(\s*['"]([^'"]+)['"]/) {
        return $1;
    }
    # Fallback: try eval (may not work in all contexts)
    {
        no strict;
        no warnings;
        local $ExtUtils::MakeMaker::_version::VERSION;
        eval "package ExtUtils::MakeMaker::_version; $line"; ## no critic
        return $ExtUtils::MakeMaker::_version::VERSION if defined $ExtUtils::MakeMaker::_version::VERSION;
    }
    return;
}

# maybe_command - check if a file is an executable command (Unix version)
sub maybe_command {
    my($self,$file) = @_;
    return unless defined $file and length $file;
    return $file if -x $file && ! -d $file;
    return;
}

1;

__END__

=head1 NAME

ExtUtils::MM_Unix - Unix-specific methods for ExtUtils::MakeMaker

=head1 DESCRIPTION

This is a PerlOnJava stub providing Unix-specific methods used by CPAN.pm.

=cut
