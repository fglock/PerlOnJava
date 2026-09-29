use strict;
use warnings;
use Test::More tests => 2;

{
    package SymbolicSelect;
    my $name = 'FH000';

    sub selected_io_ref {
        no strict 'refs';
        select select ++$name;
        return *{$name}{IO};
    }
}

my $io = SymbolicSelect::selected_io_ref();
ok(defined $io, 'symbolic select vivifies an IO slot in the lexical package');
like(ref($io), qr/^(?:IO::File|IO::Handle)$/, 'IO slot is a reference-like handle');
