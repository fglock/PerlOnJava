use strict;
use warnings;
use Test::More;

plan skip_all => 'nested launcher checks run only under PerlOnJava'
    unless $ENV{PERLONJAVA_EXECUTABLE} && -x $ENV{PERLONJAVA_EXECUTABLE};
plan skip_all => 'restricted PATH launcher check is Unix-only' if $^O eq 'MSWin32';

my $launcher = $^X;
my $status;
{
    # EnvDir -clean drops inherited launcher variables, then installs a PATH
    # with no Java executable. A nested $^X must still start from this JVM.
    local %ENV = (PATH => '/no-java-here');
    $status = system($launcher, '-e', 'exit 0');
}

is($status, 0, 'nested $^X starts after environment cleanup removes Java settings');
done_testing;
