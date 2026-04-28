use strict;
use warnings;

use Test::More tests => 1;

my $success = 0;
my $err;
{
    local $@;
    eval {
        require YAML::Dumper;
        my $hash   = {};
        my $dumper = YAML::Dumper->new();
        my $string = $dumper->dump($hash);
        $success = 1;
    };
    $err = $@;
}
is( $success, 1, "Basic YAML::Dumper usage worked as expected" )
  or diag( explain($err) );

