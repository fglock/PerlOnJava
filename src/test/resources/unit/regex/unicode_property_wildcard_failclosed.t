use strict;
use warnings;
use Test::More tests => 8;

local $ENV{JPERL_UNIMPLEMENTED};
delete $ENV{JPERL_UNIMPLEMENTED};

my @warnings;
local $SIG{__WARN__} = sub { push @warnings, @_ };

eval q!qr/\p{nv=\\\}(?0)|\337ss|\337ss//!;
like($@, qr/^Unicode property wildcard not terminated/,
    'GH17371 malformed Numeric_Value wildcard is fatal without warn mode');

eval q{qr/\p{sc=}/};
like($@, qr/^Unicode property wildcard not terminated/,
    'missing Script value is fatal without warn mode');

eval q{qr/\p{scx=}/};
like($@, qr/^Unicode property wildcard not terminated/,
    'missing Script_Extensions value is fatal without warn mode');

my $numeric = eval q{qr/\p{nv=1}/};
is($@, '', 'valid Numeric_Value property still compiles');
ok('1' =~ $numeric, 'valid Numeric_Value property still matches');

my $script = eval q{qr/\p{sc=Latin}/};
is($@, '', 'valid Script property still compiles');
ok('A' =~ $script, 'valid Script property still matches');

is(scalar @warnings, 0, 'fatal wildcard diagnostics do not warn');
