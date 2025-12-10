package TOML;

use Exporter "import";
use warnings;
use strict;

XSLoader::load( 'Toml' );

# NOTE: The core implementation is in file:
#       src/main/java/org/perlonjava/perlmodule/Toml.java

our @EXPORT_OK = qw(from_toml to_toml);
our @EXPORT = @EXPORT_OK;

1;

__END__

=head1 NAME

TOML - Parser for Tom's Obvious, Minimal Language.

=head1 SYNOPSIS

    use TOML qw(from_toml to_toml);

    # Parsing toml
    my $toml = slurp("~/.foo.toml");
    my $data = from_toml($toml);

    # With error checking
    my ($data, $err) = from_toml($toml);
    unless ($data) {
        die "Error parsing toml: $err";
    }

    # Creating toml
    my $toml = to_toml($data);

=head1 DESCRIPTION

C<TOML> implements a parser for Tom's Obvious, Minimal Language, as
defined at L<https://github.com/toml-lang/toml>. C<TOML> exports two
subroutines, C<from_toml> and C<to_toml>.

=head1 FUNCTIONS

=head2 from_toml

C<from_toml> transforms a string containing toml to a perl data
structure. This data structure complies with the TOML specification.

If called in list context, C<from_toml> produces a (C<hash>,
C<error_string>) tuple, where C<error_string> is C<undef> on
non-errors. If there is an error, then C<hash> will be undefined and
C<error_string> will contain details about said error.

=head2 to_toml

C<to_toml> transforms a perl data structure into toml-formatted
string.

=head1 SEE ALSO

L<https://github.com/toml-lang/toml>

=cut
