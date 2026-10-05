use strict;
use warnings;
use Test::More tests => 3;

sub existing_subroutine { 1 }

my $name = 'main::existing_subroutine';
ok(exists &$name, 'exists checks a dynamically named subroutine under strict refs');

$name = 'main::missing_subroutine';
ok(!exists &$name, 'exists reports a missing dynamically named subroutine under strict refs');

{
    package Local::DynamicStrictExists;
    sub package_subroutine { 1 }
    $name = 'package_subroutine';
    ::ok(exists &$name, 'exists resolves a dynamic subroutine name in the current package');
}
