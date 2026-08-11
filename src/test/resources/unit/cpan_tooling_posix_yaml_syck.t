use strict;
use warnings;
use Test::More;

use POSIX;
use YAML::Syck ();

ok(defined &floor, 'POSIX exports floor by default');
is(floor(3.75), 3, 'default floor export is callable');

my $yaml = YAML::Syck::DumpYAML({answer => 42, names => ['Ada', 'Grace']});
my $from_yaml = YAML::Syck::LoadYAML($yaml);
is($from_yaml->{answer}, 42, 'YAML::Syck round trip preserves numbers');
is_deeply($from_yaml->{names}, ['Ada', 'Grace'],
    'YAML::Syck round trip preserves arrays');

my $json = YAML::Syck::DumpJSON({answer => 42, ok => 1});
my $from_json = YAML::Syck::LoadJSON($json);
is_deeply($from_json, {answer => 42, ok => 1},
    'JSON::Syck round trip uses the bundled JSON implementation');

done_testing;
