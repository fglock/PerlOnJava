#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 1;

our $r;
our $x;
my ($first_format_ref, $second_format_ref);

sub declare_stale_lexical_format {
    my $x;
    format STALE_LEXICAL_FORMAT =
@
$r = \$x
.
}

{
    # An unrelated active lexical must not replace the format's unavailable
    # declaration-scope lexical just because both variables are named $x.
    my $x = 'unrelated active lexical';
    local $SIG{__WARN__} = sub {};
    my $fileno = fileno STALE_LEXICAL_FORMAT;
    write STALE_LEXICAL_FORMAT;
    $first_format_ref = $r;
    write STALE_LEXICAL_FORMAT;
    $second_format_ref = $r;
}

isnt($first_format_ref, $second_format_ref,
    'writes do not reuse an unrelated active lexical cell');
