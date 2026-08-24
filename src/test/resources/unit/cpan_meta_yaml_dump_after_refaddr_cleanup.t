use strict;
use warnings;
use Test::More tests => 4;

use CPAN::Meta::YAML ();

my $yaml = eval {
    CPAN::Meta::YAML::Dump({
        name    => 'String-Random',
        version => '0.32',
    });
};

is($@, '', 'CPAN::Meta::YAML Dump survives its private refaddr cleanup');
like($yaml, qr/^---\n/m, 'Dump emits a YAML document marker');
like($yaml, qr/^name: String-Random$/m, 'Dump preserves a scalar field');
like($yaml, qr/^version: '0\.32'$/m, 'Dump preserves a quoted version field');
