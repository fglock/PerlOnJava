package DBI::Const::GetInfoReturn;
use strict;
use warnings;

# Minimal stub for PerlOnJava - provides human-readable descriptions
# of DBI get_info() return values. Used by DBIx::Class for diagnostics.

sub Explain {
    my ($info_type, $value) = @_;
    return '';
}

sub Format {
    my ($info_type, $value) = @_;
    return defined $value ? "$value" : '';
}

1;
