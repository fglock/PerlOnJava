package ExtUtils::MM_Win32;
use strict;
use warnings;

our $VERSION = '7.78_perlonjava';

# MM_Win32 provides Windows-specific methods for ExtUtils::MakeMaker.
# In PerlOnJava, we only implement the methods needed by CPAN.pm.

use ExtUtils::MM_Unix;
our @ISA = qw(ExtUtils::MM_Unix);

# maybe_command - check if a file is an executable command (Windows version)
# Checks for .com, .exe, .bat, .cmd extensions
sub maybe_command {
    my($self,$file) = @_;
    my @e = exists($ENV{'PATHEXT'})
          ? split(/;/, $ENV{PATHEXT})
          : qw(.com .exe .bat .cmd);
    my $e = '';
    for (@e) { $e .= "\Q$_\E|" }
    chop $e;
    # see if file ends in one of the known extensions
    if ($file =~ /($e)$/i) {
        return $file if -e $file;
    }
    else {
        for (@e) {
            return "$file$_" if -e "$file$_";
        }
    }
    return;
}

1;

__END__

=head1 NAME

ExtUtils::MM_Win32 - Windows-specific methods for ExtUtils::MakeMaker

=head1 DESCRIPTION

This is a PerlOnJava stub providing Windows-specific methods used by CPAN.pm.

=cut
