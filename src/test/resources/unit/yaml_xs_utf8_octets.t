use strict;
use warnings;

use Encode qw(encode);
use Test::More tests => 2;
use YAML::XS qw(Load);

my $octets = encode('UTF-8', "name: \x{307b}\x{3052}\n");
my $data = Load($octets);

is($data->{name}, "\x{307b}\x{3052}", 'YAML::XS loads UTF-8 octets');
ok(utf8::is_utf8($data->{name}), 'YAML::XS returns a Unicode scalar');
