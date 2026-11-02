use strict;
use warnings;
use Test::More tests => 1;

use YAML::Syck ();

my $value = YAML::Syck::LoadJSON(<<'JSON');
{
    foo: "Welcome to [% my %] world. It is a nice [%place %].",
    bar: "Welcome to :my world. It is a nice :place."
}
JSON

is_deeply($value, {
    foo => 'Welcome to [% my %] world. It is a nice [%place %].',
    bar => 'Welcome to :my world. It is a nice :place.',
}, 'YAML::Syck LoadJSON accepts the phrasebook fixture syntax');
