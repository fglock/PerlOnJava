use strict;
use lib -e 't' ? 't' : 'test';
use lib 'inc';
use Test::YAML();
BEGIN {
    @Test::YAML::EXPORT =
        grep { not /^(Dump|Load)(File)?$/ } @Test::YAML::EXPORT;
}
use TestYAML tests => 4;
use YAML;

{
    no warnings qw'once redefine';
    require YAML::Dumper;

    local *YAML::Dumper::dump =
        sub { return 'got to dumper' };

    require YAML::Loader;
    local *YAML::Loader::load =
        sub { return 'got to loader' };

    is Dump(\%ENV), 'got to dumper',
        'Dump got to the business end';
    is Load(\%ENV), 'got to loader',
        'Load got to the business end';

    is Dump(\%ENV), 'got to dumper',
        'YAML::Dump got to the business end';
    is Load(\%ENV), 'got to loader',
        'YAML::Load got to the business end';
}
