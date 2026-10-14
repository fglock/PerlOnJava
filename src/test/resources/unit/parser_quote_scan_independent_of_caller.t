use strict;
use warnings;
use Test::More;

{
    package Local::ParserQuotedArgument;
    BEGIN { Test::More->import() }
    our $seen;
    sub capture { $seen = $_[0]; return 'captured' }

    sub dispatch {
        my ($name) = @_;
        {
            run => sub { capture "opening [ bracket stays inside the string" },
            check_is => sub { is "opening [ bracket", "opening [ bracket", "is accepts a bare quoted argument" },
            check_like => sub { like "opening [ bracket", qr/opening/, "like accepts a bare quoted argument" },
        }->{$name}->();
    }
}

is(Local::ParserQuotedArgument::dispatch('run'), 'captured',
    'callback table dispatch accepts a quoted argument after an ordinary sub name');
is($Local::ParserQuotedArgument::seen, 'opening [ bracket stays inside the string',
    'brace scanning ignores punctuation inside a string without a parser-known caller');
Local::ParserQuotedArgument::dispatch('check_is');
Local::ParserQuotedArgument::dispatch('check_like');

done_testing;
