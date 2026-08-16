use strict;
use warnings;
use Test::More tests => 3;
use YAML::PP;

my $yaml = YAML::PP->new(header => 0)->dump({ answer => 42 });
like($yaml, qr/^answer:\s+42\s*$/m, 'object dump method emits YAML');
ok(YAML::PP->new->can('dump'), 'object dump method is discoverable');

my $true = 1;
my $yaml_ref = YAML::PP->new(header => 0, schema => [qw(Core Perl)])
    ->dump(bless \$true, 'Local::Boolean');
like($yaml_ref, qr/1/, 'blessed scalar reference serializes its referent');
