use strict;
use warnings;
use Test::More;

# Standard Perl applies regex substitution to the UTF-8 octets when `use
# bytes` is active. This is the reduced behavior needed by
# WWW::Form::UrlEncoded::PP. Keep the assertions as TODO until both backends
# preserve the original lvalue while using the byte view.
my $value = "\x{263A}";
{
    use bytes;
    $value =~ s/./X/g;
}

TODO: {
    local $TODO = 'PerlOnJava compiler does not yet preserve use-bytes substitution semantics';
    is($value, 'XXX', 'use bytes makes regex substitution visit each UTF-8 octet');
}

my $encoded = "\x{263A}";
{
    use bytes;
    $encoded =~ s/([^A-Za-z0-9\-\._~])/sprintf('%%%02X', ord($1))/gsxe;
}

TODO: {
    local $TODO = 'PerlOnJava compiler does not yet preserve use-bytes substitution semantics';
    is($encoded, '%E2%98%BA', 'use bytes supports octet-wise URL escaping');
}

done_testing;
