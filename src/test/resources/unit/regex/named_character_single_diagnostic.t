use strict;
use warnings;
use Test::More tests => 2;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, join '', @_ };
    eval "#line 41 named_character_single_diagnostic.t\nqr/abc\\N{def}/";
}

is($@,
    "Unknown charname 'def' at named_character_single_diagnostic.t line 41, within pattern\n",
    'an unknown literal charname has one exact lexical diagnostic');
is(scalar @warnings, 0,
    'native compilation does not emit a second warning');
