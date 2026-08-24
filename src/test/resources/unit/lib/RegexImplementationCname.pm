package RegexImplementationCname;

use strict;
use warnings;

our $Evil = 'A';

sub translator {
    my ($name) = @_;
    if ($name eq 'EVIL') {
        (my $next = substr("A$Evil", -1))++;
        my $result = $Evil;
        $Evil .= $next;
        return $result;
    }
    return '' if $name eq 'EMPTY-STR';
    return $name;
}

sub import {
    $^H{charnames} = \&translator;
}

1;
