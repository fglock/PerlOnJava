use strict;
use warnings;

use File::Spec;
use Scalar::Util qw(refaddr);
use Test::More tests => 2;

{
    package Local::UnlinkingHandle;
    use Scalar::Util qw(refaddr);

    our %PATH_FOR;

    sub new {
        my ($class, $path) = @_;
        open my $handle, '>', $path or die "open $path: $!";
        bless $handle, $class;
        $PATH_FOR{refaddr($handle)} = $path;
        return $handle;
    }

    sub DESTROY {
        my ($self) = @_;
        my $path = delete $PATH_FOR{refaddr($self)};
        close $self;
        unlink $path if defined $path;
    }
}

my $path = File::Spec->catfile(
    File::Spec->tmpdir,
    join('-', 'perlonjava-blessed-glob-destroy', $$, time, int(rand(1_000_000))),
);

END { unlink $path if defined $path && -e $path }

my $handle = Local::UnlinkingHandle->new($path);
ok(-f $path, 'blessed glob owns the created file');
undef $handle;
ok(!-e $path, 'discarding a blessed glob runs DESTROY immediately');
