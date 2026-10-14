use strict;
use warnings;
use Test::More tests => 3;
use File::Temp qw(tempfile);
use IO::Handle;

{
    package Local::BlessedIOHandle;
    use parent qw(IO::Handle);

    sub path {
        my $self = shift;
        ${*$self}{+__PACKAGE__} = shift if @_;
        return ${*$self}{+__PACKAGE__};
    }
}

sub set_path {
    my ($handle, $path) = @_;
    bless $handle, 'Local::BlessedIOHandle';
    $handle->path($path);
}

my ($handle, $path) = tempfile();
print {$handle} "static message\n";
$handle->flush;
my $descriptor = fileno($handle);
seek $handle, 0, 0 or die "seek: $!";

set_path($handle, $path);

is(fileno($handle), $descriptor,
    'blessing a filehandle in a helper preserves its descriptor');
is($handle->path, $path,
    'the IO::Handle subclass can store per-handle metadata');
is($handle->getline, "static message\n",
    'the blessed filehandle remains readable');
