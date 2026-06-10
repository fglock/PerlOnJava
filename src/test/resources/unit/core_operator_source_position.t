use strict;
use warnings;
use Test::More tests => 5;

my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning = shift };
    eval qq{#line 1 "core-position-warning.pl"\nwarn;\n};
}

is($warning,
    "Warning: something's wrong at core-position-warning.pl line 1.\n",
    'zero-argument warn uses the operator token source line');

$warning = '';
{
    local $SIG{__WARN__} = sub { $warning = shift };
    eval qq{#line 1 "core-position-warning-eof.pl"\nwarn\n};
}

is($warning,
    "Warning: something's wrong at core-position-warning-eof.pl line 1.\n",
    'EOF-terminated zero-argument warn uses the operator token source line');

my $ok = eval qq{#line 7 "core-position-die.pl"\ndie;\n1};

is($ok, undef, 'zero-argument die throws');
is($@,
    "Died at core-position-die.pl line 7.\n",
    'zero-argument die uses the operator token source line');

$@ = '';
eval qq{#line 9 "core-position-die-eof.pl"\ndie\n};
is($@,
    "Died at core-position-die-eof.pl line 9.\n",
    'EOF-terminated zero-argument die uses the operator token source line');
