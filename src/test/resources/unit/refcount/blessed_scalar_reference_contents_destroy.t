use strict;
use warnings;

use File::Basename qw(dirname);
use File::Temp;
use Test::More tests => 4;

{
    package Local::ScalarWrapper;

    use overload '""' => sub { ${$_[0]} }, fallback => 1;

    sub new {
        my ($class, $value) = @_;
        return bless \$value, ref($class) || $class;
    }

    sub identity { $_[0] }
    sub dirname { $_[0]->new(File::Basename::dirname(${$_[0]})) }
}

my $directory = File::Temp::tempdir(CLEANUP => 1);
my $wrapper = Local::ScalarWrapper->new(File::Temp->new(DIR => $directory));
my $path = "$wrapper";

ok(-f $path, 'blessed scalar reference retains temporary file object');
my $parent = "" . $wrapper->dirname;
is($parent, $directory, 'method returning another scalar wrapper preserves original');
undef $wrapper;
ok(!-e $path, 'discarding blessed scalar reference destroys temporary file object');

my $scope_path;
{
    my $second = Local::ScalarWrapper->new(File::Temp->new(DIR => $directory));
    $scope_path = "$second";
}
ok(!-e $scope_path, 'scope exit destroys temporary file object in blessed scalar reference');
