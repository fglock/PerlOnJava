use strict;
use warnings;

use Test::More tests => 16;

use Hash::Util::FieldHash qw(:all);

my @exports = qw(fieldhash fieldhashes idhash idhashes id id_2obj register);

for my $export (@exports) {
    ok(defined &{$export}, ":all imports $export");
}

{
    package Local::FieldHashCompat;

    # Hash::Util::FieldHash::Compat uses this delegation when the bundled
    # implementation is available.
    Hash::Util::FieldHash->import(':all');
}

for my $export (@exports) {
    ok(defined &{"Local::FieldHashCompat::$export"},
        "method-style :all delegation imports $export");
}

is id('cache-key'), 'cache-key', 'id preserves a scalar cache key';

my %seen;
@seen{map { id($_) } qw(foo bar)} = (1, 1);
is_deeply [sort keys %seen], [qw(bar foo)],
    'scalar ids remain distinct when used as hash keys';
