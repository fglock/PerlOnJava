package Text::CSV_XS;

# Text::CSV_XS - Pure-Perl XS replacement for PerlOnJava
#
# This module inherits from Text::CSV_PP and provides the Text::CSV_XS
# interface. Text::CSV will detect this module and prefer it over CSV_PP.
#
# The CPAN Text::CSV wrapper uses \&{"Text::CSV_XS::$method"} to alias
# PublicMethods. Since inherited methods aren't in the stash, we must
# explicitly install them so the alias lookup succeeds.

use strict;
use warnings;

use Text::CSV_PP;
use vars qw( $VERSION @ISA @EXPORT_OK %EXPORT_TAGS );

$VERSION = "1.61";
@ISA     = qw( Text::CSV_PP );

# Re-export everything from CSV_PP
@EXPORT_OK   = @Text::CSV_PP::EXPORT_OK;
%EXPORT_TAGS = %Text::CSV_PP::EXPORT_TAGS;

# Text::CSV's _load() does \&{"Text::CSV_XS::$method"} for each PublicMethod.
# Since we inherit from CSV_PP, those symbols aren't in our stash directly.
# Install them so the alias resolves.
{
    no strict 'refs';
    for my $method (qw(
        version error_diag error_input known_attributes
        PV IV NV CSV_TYPE_PV CSV_TYPE_IV CSV_TYPE_NV
        CSV_FLAGS_IS_QUOTED CSV_FLAGS_IS_BINARY
        CSV_FLAGS_ERROR_IN_FIELD CSV_FLAGS_IS_MISSING
    )) {
        next if defined &{"Text::CSV_XS::$method"};
        if (my $ref = Text::CSV_PP->can($method)) {
            *{"Text::CSV_XS::$method"} = $ref;
        }
    }
}

1;
