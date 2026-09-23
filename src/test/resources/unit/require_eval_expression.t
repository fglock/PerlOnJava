use strict;
use warnings;
use Test::More;

my $result = eval { require eval q{q{definitely-not-a-module.pm}}; 1 };

ok !$result, 'require evaluates a computed filename expression';
like $@, qr/^Can't locate definitely-not-a-module\.pm in \@INC /,
    'require reports the computed filename';

eval { require 'No/Such./Module.pm' };
unlike $@, qr/you may need to install/, 'invalid module path gets no install hint';

eval { require 'missing-header.ph' };
like $@, qr/did you run h2ph\?/, 'missing .ph file gets h2ph advice';

eval { no warnings 'syscalls'; require "missing\0file.pm" };
like $@, qr/^Can't locate missing\\0file\.pm: /, 'embedded NUL never reaches the filesystem';

done_testing;
