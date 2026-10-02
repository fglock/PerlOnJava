use strict;
use warnings;
use Test::More;
use File::Basename 'dirname';
use File::Temp qw(tempdir);
use Cwd 'getcwd';

my $dir = tempdir(CLEANUP => 1);
open my $fixture, '>', "$dir/util.pl" or die "create util.pl: $!";
print {$fixture} "sub api_key { 'fixture-key' }\n1;\n";
close $fixture or die "close util.pl: $!";

my $original_dir = getcwd();
chdir $dir or die "chdir $dir: $!";
{
    local $0 = '05_api_baseclass.t';
    my $result = do (dirname $0) . '/util.pl';
    is($result, 1, 'do loads the complete concatenated filename expression');
    ok(defined(&main::api_key), 'do exposes the loaded package subroutine');
    is(defined(&main::api_key) ? main::api_key() : undef,
        'fixture-key', 'the loaded package subroutine is callable');
}
chdir $original_dir or die "restore working directory: $!";

done_testing;
