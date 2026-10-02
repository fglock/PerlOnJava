use strict;
use warnings;
use Test::More;
use File::Basename 'dirname';
use File::Temp qw(tempdir);
use Cwd 'getcwd';

my $dir = tempdir(CLEANUP => 1);
mkdir "$dir/t" or die "create t directory: $!";
open my $key, '>', "$dir/t/testing.key" or die "create testing.key: $!";
print {$key} "fixture-key\n";
close $key or die "close testing.key: $!";

open my $fixture, '>', "$dir/t/util.pl" or die "create util.pl: $!";
print {$fixture} <<'FIXTURE';
sub api_key {
    (my $keyfile = __FILE__) =~ s|/(.*?)$|/|;
    $keyfile .= 'testing.key';
    open my $fh, '<', $keyfile or die "Cannot open $keyfile: $!, stopped";
    chomp(my $key = <$fh>);
    return $key;
}
1;
FIXTURE
close $fixture or die "close util.pl: $!";

my $original_dir = getcwd();
chdir $dir or die "chdir $dir: $!";
{
    local $0 = 't/05_api_baseclass.t';
    local @INC = ('.', @INC);
    my $result = do (dirname $0) . '/util.pl';
    is($result, 1, 'do loads the complete concatenated filename expression');
    ok(defined(&main::api_key), 'do exposes the loaded package subroutine');
    my $key = eval { defined(&main::api_key) ? main::api_key() : undef };
    is($key, 'fixture-key', 'a path derived from __FILE__ finds the sibling fixture')
        or diag $@;
}
chdir $original_dir or die "restore working directory: $!";

done_testing;
