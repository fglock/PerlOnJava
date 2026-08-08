use strict;
use warnings;

use Test::More tests => 2;

my @values = qw(old values);
is_deeply(\@values, [qw(old values)], 'first lexical declaration has its own pad slot');

my @values = qw(new);
is_deeply(\@values, [qw(new)], 'same-scope redeclaration gets a fresh pad slot');
