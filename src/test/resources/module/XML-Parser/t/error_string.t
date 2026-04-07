use strict;
use warnings;
use Test::More tests => 5;
use XML::Parser;

# Test the XS ErrorString() function (PPCODE return path).
# PR #184 converted this from CODE/char* to PPCODE/void with XPUSHs.

# Error code 2 is XML_ERROR_SYNTAX (always present in libexpat)
my $msg = XML::Parser::Expat::ErrorString(2);
ok( defined $msg, 'ErrorString returns a defined value for a valid error code' );
like( $msg, qr/syntax/i, 'ErrorString(2) mentions syntax' );

# Error code 1 is XML_ERROR_NO_MEMORY
my $nomem = XML::Parser::Expat::ErrorString(1);
ok( defined $nomem, 'ErrorString returns a defined value for error code 1' );
like( $nomem, qr/memory/i, 'ErrorString(1) mentions memory' );

# Verify that a parse error produces a message containing ErrorString output
my $parser = XML::Parser->new;
eval { $parser->parse('<broken'); };
like( $@, qr/\S/, 'parse error produces a non-empty error message' );
