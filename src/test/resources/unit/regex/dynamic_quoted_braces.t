use strict;
use warnings;
use Test::More tests => 4;
use re 'eval';

ok('a{' =~ '^a(??{"{"})$',
    'runtime string dynamic pattern may return an opening brace');
ok('a}' =~ '^a(??{"}"})$',
    'runtime string dynamic pattern may return a closing brace');

my $opening = '^a(??{"{"})$';
my $closing = '^a(??{"}"})$';
ok('a{' =~ $opening,
    'scalar runtime pattern preserves an opening brace inside quoted code');
ok('a}' =~ $closing,
    'scalar runtime pattern preserves a closing brace inside quoted code');
