package XString;

use strict;
use warnings;

our $VERSION = '0.005';

use B ();

sub perlstring {
    return B::perlstring(@_);
}

sub cstring {
    my ($str) = @_;
    die "Usage: XString::cstring(sv)" unless @_ == 1 && defined $str;

    utf8::encode($str);

    $str =~ s{([\\\"\x00-\x1f\x7f-\xff])}{
        my $ord = ord($1);
        $ord == 7  ? '\\a'
      : $ord == 8  ? '\\b'
      : $ord == 9  ? '\\t'
      : $ord == 10 ? '\\n'
      : $ord == 11 ? '\\v'
      : $ord == 12 ? '\\f'
      : $ord == 13 ? '\\r'
      : $ord == 34 ? '\\"'
      : $ord == 92 ? '\\\\'
      : sprintf('\\%03o', $ord)
    }gex;

    return qq{"$str"};
}

1;

__END__

=head1 NAME

XString - PerlOnJava string quoting helpers

=head1 DESCRIPTION

Pure-Perl replacement for the CPAN XString XS module. It provides the
small API needed by modules such as Specio.

=cut
