use strict;
use lib -e 't' ? 't' : 'test';
use lib 'inc';
use Test::YAML();
BEGIN {
    @Test::YAML::EXPORT =
        grep { not /^(Dump|Load)(File)?$/ } @Test::YAML::EXPORT;
}
use TestYAML tests => 3;

use YAML;

ok defined(&Dump),
    'Dump() is exported';
ok defined(&Load),
    'Load() is exported';
ok not(defined &Store),
    'Store() is not exported';
