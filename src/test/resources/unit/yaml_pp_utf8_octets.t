use strict;
use warnings;
use Test::More tests => 2;
use Encode qw(encode);
use YAML::PP qw(Load);

my $octets = encode('UTF-8', "name: \x{307b}\x{3052}\n");
my $data = Load(Encode::decode('UTF-8', $octets));

is($data->{name}, "\x{307b}\x{3052}", 'YAML loader decodes UTF-8 octets');
ok(utf8::is_utf8($data->{name}), 'loaded non-ASCII YAML scalar is Unicode');
