#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

BEGIN {
    eval {
        require Sub::Quote;
        Sub::Quote->import(qw(qsub quoted_from_sub unquote_sub));
        1;
    } or plan skip_all => 'Sub::Quote not available';
}

plan tests => 3;

my $quoted = qsub q{ $_[0] };
ok(quoted_from_sub($quoted), 'qsub metadata survives after deferred sub creation');

my $unquoted = unquote_sub($quoted);
ok($unquoted, 'qsub can be unquoted after metadata lookup');
is($unquoted->('ok'), 'ok', 'unquoted qsub remains callable');
