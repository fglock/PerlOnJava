use strict;
use warnings;
use Test::More tests => 6;
use Text::Balanced qw(extract_delimited extract_bracketed extract_quotelike);

# Test extract_delimited for quotes
my $text = q{"Hello, world!" and 'another string'};
my ($extracted, $remainder) = extract_delimited($text, '"');
is($extracted, '"Hello, world!"', 'Extracted double quoted string');
is($remainder, q{ and 'another string'}, 'Remainder after double quote extraction');

# Test extract_bracketed for nested brackets
my $nested = "{ outer { inner } more }";
($extracted, $remainder) = extract_bracketed($nested, '{');
is($extracted, "{ outer { inner } more }", 'Extracted nested brackets');
is($remainder, "", 'No remainder after bracket extraction');

# Test extract_quotelike for Perl quotes
my $perl_quotes = q{qq/Hello there/ . q{world}};
($extracted, $remainder) = extract_quotelike($perl_quotes);
is($extracted, 'qq/Hello there/', 'Extracted qq// quote');
is($remainder, q{ . q{world}}, 'Remainder after quotelike extraction');

