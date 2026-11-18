use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

our $destroyed = 0;
our %cleanup;

{
    package PackageRootNestedArrayHandle;

    sub DESTROY {
        my ($self) = @_;
        close $self;
        $main::destroyed++;
    }
}

my ($handle, $filename) = tempfile();
$cleanup{files} = [[ $handle, $filename ]];
bless $handle, 'PackageRootNestedArrayHandle';
undef $handle;
is($destroyed, 0, 'nested package-root array keeps its filehandle object alive');

delete $cleanup{files};
unlink $filename or die "unlink $filename: $!";

done_testing;
