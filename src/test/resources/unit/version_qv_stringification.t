use strict;
use warnings;
use Test::More tests => 3;
use version;

my $version = qv('1.2.31');
is("$version", '1.2.31', 'qv stringification preserves the supplied dotted form');
ok($version->is_qv, 'qv retains dotted-decimal comparison semantics');
is($version->normal, 'v1.2.31', 'normal still returns normalized v-string form');
