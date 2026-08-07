use strict;
use warnings;
use Test::More;

# Standard Perl applies regex substitution to the UTF-8 octets when `use
# bytes` is active. This is the reduced behavior needed by
# WWW::Form::UrlEncoded::PP.
my $value = "\x{263A}";
{
    use bytes;
    $value =~ s/./X/g;
}

is($value, 'XXX', 'use bytes makes regex substitution visit each UTF-8 octet');

my $encoded = "\x{263A}";
{
    use bytes;
    $encoded =~ s/([^A-Za-z0-9\-\._~])/sprintf('%%%02X', ord($1))/gsxe;
}

is($encoded, '%E2%98%BA', 'use bytes supports octet-wise URL escaping');

my @octets;
{
    use bytes;
    my $matched = "\x{263A}";
    push @octets, sprintf('%02X', ord($1)) while $matched =~ /(.)/g;
}
is(join('', @octets), 'E298BA', 'use bytes makes plain regex matching visit UTF-8 octets');

done_testing;
