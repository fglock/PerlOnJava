package Parse::VarName;

use strict;
use warnings;

our $VERSION = '0.03';

sub split_varname_words {
    my %args = @_ == 1 ? (varname => $_[0]) : @_;
    my $name = defined $args{varname} ? $args{varname} : '';
    $name =~ s/[^A-Za-z0-9]+/ /g;
    $name =~ s/([a-z0-9])([A-Z])/$1 $2/g;
    $name =~ s/([A-Z]+)([A-Z][a-z])/$1 $2/g;
    my @words = grep length, split /\s+/, $name;
    return \@words;
}

1;
