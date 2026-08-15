use strict;
use warnings FATAL => 'all';
use Test::More;

require HTTP::Tiny;

{
    package Local::HTTP::Tiny;
    our @ISA = ('HTTP::Tiny');
    sub request { return $_[1] }
}

my $client = bless {}, 'Local::HTTP::Tiny';
for my $method (qw(get head put post patch delete)) {
    ok(HTTP::Tiny->can($method), "HTTP::Tiny installs $method once without warnings");
    is($client->$method('http://example.test/'), uc($method), "$method dispatches the correct HTTP verb");
}

done_testing;
