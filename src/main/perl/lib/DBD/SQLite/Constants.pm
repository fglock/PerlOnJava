package DBD::SQLite::Constants;

use strict;
use warnings;
use Exporter 'import';

our $VERSION = '1.74';
our @EXPORT_OK = qw(
    SQLITE_OPEN_READONLY
    DBD_SQLITE_STRING_MODE_UNICODE_FALLBACK
);

sub SQLITE_OPEN_READONLY () { 1 }
sub DBD_SQLITE_STRING_MODE_UNICODE_FALLBACK () { 5 }

1;
