use strict;
use warnings;
use Test::More tests => 1;

my $payload = join "\n", map { $_ x 7 } qw(< = >);
eval "1;\n__DATA__\n$payload\n";
is($@, '', 'conflict-marker payload after __DATA__ remains data');
